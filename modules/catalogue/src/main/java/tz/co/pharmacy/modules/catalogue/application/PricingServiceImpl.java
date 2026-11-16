package tz.co.pharmacy.modules.catalogue.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.catalogue.domain.BranchPrice;
import tz.co.pharmacy.modules.catalogue.domain.Category;
import tz.co.pharmacy.modules.catalogue.domain.PriceList;
import tz.co.pharmacy.modules.catalogue.domain.PriceListItem;
import tz.co.pharmacy.modules.catalogue.domain.Product;
import tz.co.pharmacy.modules.catalogue.domain.Promotion;
import tz.co.pharmacy.modules.catalogue.persistence.BranchPriceRepository;
import tz.co.pharmacy.modules.catalogue.persistence.CategoryRepository;
import tz.co.pharmacy.modules.catalogue.persistence.PriceListRepository;
import tz.co.pharmacy.modules.catalogue.persistence.ProductRepository;
import tz.co.pharmacy.modules.catalogue.persistence.PromotionRepository;
import tz.co.pharmacy.modules.catalogue.ports.inbound.PricingService;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.kernel.TenantCalendar;

@Service
@RequiredArgsConstructor
class PricingServiceImpl implements PricingService {

  private static final BigDecimal HUNDRED = new BigDecimal("100");

  private final PriceListRepository lists;
  private final BranchPriceRepository branchPrices;
  private final PromotionRepository promotions;
  private final ProductRepository products;
  private final CategoryRepository categories;
  private final TenantCalendar calendar;

  @Override
  @Transactional(readOnly = true)
  public List<PriceListView> priceLists() {
    List<PriceList> all = lists.findAllByOrderByNameAsc();
    Map<UUID, String> names =
        names(all.stream().flatMap(l -> l.getItems().stream()).map(PriceListItem::getProductUid));
    return all.stream().map(l -> view(l, names)).toList();
  }

  @Override
  @Transactional
  public PriceListView savePriceList(UUID uid, PriceListCommand c) {
    String name = Checks.text(c.name(), "name", 120);
    if (uid == null
        ? lists.existsByNameIgnoreCase(name)
        : lists.existsByNameIgnoreCaseAndUidNot(name, uid)) {
      throw Refusal.conflict("duplicate_price_list", "Another price list is called " + name + ".");
    }
    String type = c.customerType() == null || c.customerType().isBlank() ? null : c.customerType();
    if (type != null && c.customerUid() != null) {
      throw Refusal.invalid(
          "price_list_target",
          "A price list is for a customer type or for one customer, not both.",
          "customerUid");
    }
    if (c.validFrom() != null && c.validTo() != null && c.validTo().isBefore(c.validFrom())) {
      throw Refusal.invalid("invalid_period", "validTo is before validFrom.", "validTo");
    }
    BigDecimal adjustment = c.adjustmentPercent() == null ? BigDecimal.ZERO : c.adjustmentPercent();
    if (adjustment.compareTo(new BigDecimal("-100")) <= 0) {
      throw Refusal.invalid(
          "invalid_adjustment", "adjustmentPercent must be above -100.", "adjustmentPercent");
    }
    PriceList list =
        uid == null
            ? new PriceList(name)
            : lists
                .findByUid(uid)
                .orElseThrow(() -> Refusal.notFound("price_list_not_found", "No such price list."));
    list.change(
        name,
        type,
        c.customerUid(),
        c.customerName(),
        adjustment,
        c.validFrom(),
        c.validTo(),
        c.active() == null || c.active());
    List<PriceListItem> items = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    List<ItemCommand> commands = c.items() == null ? List.of() : c.items();
    for (int i = 0; i < commands.size(); i++) {
      ItemCommand item = commands.get(i);
      String field = "items[" + i + "]";
      UUID product = Checks.required(item.productUid(), field + ".productUid");
      int min = item.minQuantity() == null ? 1 : item.minQuantity();
      if (min < 1) {
        throw Refusal.invalid("invalid_quantity", "minQuantity must be at least 1.", field);
      }
      BigDecimal price = Checks.required(item.price(), field + ".price");
      if (price.signum() < 0) {
        throw Refusal.invalid("invalid_price", "price cannot be negative.", field + ".price");
      }
      if (!seen.add(product + ":" + min)) {
        throw Refusal.invalid(
            "duplicate_item", "The same product and minimum quantity appear twice.", field);
      }
      items.add(new PriceListItem(product, min, Amounts.money(price)));
    }
    Set<UUID> known =
        products.findByUidIn(items.stream().map(PriceListItem::getProductUid).toList()).stream()
            .map(Product::getUid)
            .collect(Collectors.toSet());
    for (PriceListItem item : items) {
      if (!known.contains(item.getProductUid())) {
        throw Refusal.invalid("product_not_found", "No such product.", "items");
      }
    }
    list.items(items);
    PriceList saved = lists.save(list);
    return view(saved, names(saved.getItems().stream().map(PriceListItem::getProductUid)));
  }

  @Override
  @Transactional
  public void deletePriceList(UUID uid) {
    lists.delete(
        lists
            .findByUid(uid)
            .orElseThrow(() -> Refusal.notFound("price_list_not_found", "No such price list.")));
  }

  @Override
  @Transactional(readOnly = true)
  public List<BranchPriceView> branchPrices() {
    List<BranchPrice> all = branchPrices.findAllByOrderByProductUidAsc();
    Map<UUID, String> names = names(all.stream().map(BranchPrice::getProductUid));
    return all.stream()
        .map(
            b ->
                new BranchPriceView(
                    b.getProductUid(),
                    names.get(b.getProductUid()),
                    b.getBranchUid(),
                    b.getPrice()))
        .toList();
  }

  @Override
  @Transactional
  public void setBranchPrice(UUID productUid, UUID branchUid, BigDecimal price) {
    Checks.required(productUid, "productUid");
    Checks.required(branchUid, "branchUid");
    if (products.findByUid(productUid).isEmpty()) {
      throw Refusal.invalid("product_not_found", "No such product.", "productUid");
    }
    Optional<BranchPrice> existing =
        branchPrices.findByProductUidAndBranchUid(productUid, branchUid);
    if (price == null) {
      existing.ifPresent(branchPrices::delete);
      return;
    }
    if (price.signum() < 0) {
      throw Refusal.invalid("invalid_price", "price cannot be negative.", "price");
    }
    existing.ifPresentOrElse(
        b -> b.price(Amounts.money(price)),
        () -> branchPrices.save(new BranchPrice(productUid, branchUid, Amounts.money(price))));
  }

  @Override
  @Transactional(readOnly = true)
  public List<PromotionView> promotions() {
    List<Promotion> all = promotions.findAllByOrderByValidFromDesc();
    Map<UUID, String> productNames =
        names(all.stream().map(Promotion::getProductUid).filter(u -> u != null));
    Map<UUID, String> categoryNames =
        categories.findAllByOrderByNameAsc().stream()
            .collect(Collectors.toMap(Category::getUid, Category::getName, (a, b) -> a));
    return all.stream().map(p -> view(p, productNames, categoryNames)).toList();
  }

  @Override
  @Transactional
  public PromotionView savePromotion(UUID uid, PromotionCommand c) {
    String name = Checks.text(c.name(), "name", 120);
    BigDecimal percent = Checks.required(c.percentOff(), "percentOff");
    if (percent.signum() <= 0 || percent.compareTo(HUNDRED) > 0) {
      throw Refusal.invalid(
          "invalid_percent", "percentOff must be more than 0 and at most 100.", "percentOff");
    }
    LocalDate from = Checks.required(c.validFrom(), "validFrom");
    // No end date: the promotion runs until it is switched off.
    LocalDate to = c.validTo();
    if (to != null && to.isBefore(from)) {
      throw Refusal.invalid("invalid_period", "validTo is before validFrom.", "validTo");
    }
    if (c.productUid() != null && products.findByUid(c.productUid()).isEmpty()) {
      throw Refusal.invalid("product_not_found", "No such product.", "productUid");
    }
    if (c.categoryUid() != null && categories.findByUid(c.categoryUid()).isEmpty()) {
      throw Refusal.invalid("category_not_found", "No such category.", "categoryUid");
    }
    Promotion promotion =
        uid == null
            ? new Promotion(name)
            : promotions
                .findByUid(uid)
                .orElseThrow(() -> Refusal.notFound("promotion_not_found", "No such promotion."));
    promotion.change(
        name,
        percent,
        c.productUid(),
        c.categoryUid(),
        c.branchUid(),
        from,
        to,
        c.active() == null || c.active());
    Promotion saved = promotions.save(promotion);
    return view(
        saved,
        names(
            saved.getProductUid() == null
                ? java.util.stream.Stream.empty()
                : java.util.stream.Stream.of(saved.getProductUid())),
        categories.findAllByOrderByNameAsc().stream()
            .collect(Collectors.toMap(Category::getUid, Category::getName, (a, b) -> a)));
  }

  @Override
  @Transactional
  public void deletePromotion(UUID uid) {
    promotions.delete(
        promotions
            .findByUid(uid)
            .orElseThrow(() -> Refusal.notFound("promotion_not_found", "No such promotion.")));
  }

  @Override
  @Transactional(readOnly = true)
  public List<PricedLine> price(
      UUID branchUid,
      String customerType,
      UUID customerUid,
      List<PriceRequest> lines,
      LocalDate on) {
    if (lines == null || lines.isEmpty()) {
      return List.of();
    }
    LocalDate day = on == null ? calendar.today() : on;
    Map<UUID, Product> byUid =
        products.findByUidIn(lines.stream().map(PriceRequest::productUid).toList()).stream()
            .collect(Collectors.toMap(Product::getUid, Function.identity()));
    Map<UUID, BigDecimal> branch = new HashMap<>();
    if (branchUid != null) {
      branchPrices
          .findByBranchUidAndProductUidIn(branchUid, byUid.keySet())
          .forEach(b -> branch.put(b.getProductUid(), b.getPrice()));
    }
    Optional<PriceList> list = listFor(customerUid, customerType, day);
    List<Promotion> running =
        promotions.runningOn(day).stream()
            .filter(p -> p.getBranchUid() == null || p.getBranchUid().equals(branchUid))
            .toList();
    Map<UUID, Set<UUID>> categoryTrees = new HashMap<>();
    List<PricedLine> out = new ArrayList<>();
    for (PriceRequest request : lines) {
      Product product = byUid.get(request.productUid());
      if (product == null) {
        throw Refusal.invalid("product_not_found", "No such product.", "lines");
      }
      int quantity = Math.max(request.quantity(), 1);
      BigDecimal retail = product.getSellingPrice();
      BigDecimal price = retail;
      String source = "RETAIL";
      String label = null;
      if (branch.containsKey(product.getUid())) {
        price = branch.get(product.getUid());
        source = "BRANCH";
      }
      if (list.isPresent()) {
        PriceList l = list.get();
        Optional<PriceListItem> item =
            l.getItems().stream()
                .filter(i -> i.getProductUid().equals(product.getUid()))
                .filter(i -> i.getMinQuantity() <= quantity)
                .max(Comparator.comparingInt(PriceListItem::getMinQuantity));
        if (item.isPresent()) {
          price = item.get().getPrice();
          source = "LIST";
          label = l.getName();
        } else if (l.getAdjustmentPercent().signum() != 0) {
          price = adjust(price, l.getAdjustmentPercent());
          source = "LIST";
          label = l.getName();
        }
      }
      Promotion best = null;
      BigDecimal bestPrice = price;
      for (Promotion p : running) {
        if (!applies(p, product, categoryTrees)) {
          continue;
        }
        BigDecimal promoted = adjust(price, p.getPercentOff().negate());
        if (promoted.compareTo(bestPrice) < 0) {
          bestPrice = promoted;
          best = p;
        }
      }
      if (best != null) {
        price = bestPrice;
        source = "PROMOTION";
        label = best.getName();
      }
      BigDecimal cost = product.getCostPrice();
      out.add(
          new PricedLine(
              product.getUid(),
              quantity,
              Amounts.money(price),
              Amounts.money(retail),
              source,
              label,
              cost != null && price.compareTo(cost) < 0));
    }
    return out;
  }

  /** The customer's own list, else their type's; the newest valid one if several. */
  private Optional<PriceList> listFor(UUID customerUid, String customerType, LocalDate day) {
    if (customerUid != null) {
      Optional<PriceList> own =
          lists.findByCustomerUid(customerUid).stream()
              .filter(l -> l.validOn(day))
              .max(Comparator.comparing(PriceList::getCreatedAt));
      if (own.isPresent()) {
        return own;
      }
    }
    if (customerType == null || customerType.isBlank()) {
      return Optional.empty();
    }
    return lists.findByCustomerType(customerType).stream()
        .filter(l -> l.validOn(day))
        .max(Comparator.comparing(PriceList::getCreatedAt));
  }

  private boolean applies(Promotion p, Product product, Map<UUID, Set<UUID>> trees) {
    if (p.getProductUid() != null) {
      return p.getProductUid().equals(product.getUid());
    }
    if (p.getCategoryUid() != null) {
      if (product.getCategory() == null) {
        return false;
      }
      Set<UUID> tree = trees.computeIfAbsent(p.getCategoryUid(), this::descendants);
      return tree.contains(product.getCategory().getUid());
    }
    return true;
  }

  private Set<UUID> descendants(UUID categoryUid) {
    Set<UUID> uids = new HashSet<>();
    uids.add(categoryUid);
    for (Category category : categories.findAllByOrderByNameAsc()) {
      for (Category up = category; up != null; up = up.getParent()) {
        if (up.getUid().equals(categoryUid)) {
          uids.add(category.getUid());
          break;
        }
      }
    }
    return uids;
  }

  /** {@code price} moved by {@code percent} (negative for less), rounded to cents. */
  private static BigDecimal adjust(BigDecimal price, BigDecimal percent) {
    return price
        .multiply(HUNDRED.add(percent))
        .divide(HUNDRED, 2, RoundingMode.HALF_UP)
        .max(BigDecimal.ZERO);
  }

  private Map<UUID, String> names(java.util.stream.Stream<UUID> uids) {
    List<UUID> list = uids.distinct().toList();
    if (list.isEmpty()) {
      return Map.of();
    }
    return products.findByUidIn(list).stream()
        .collect(Collectors.toMap(Product::getUid, Product::getName));
  }

  private static PriceListView view(PriceList l, Map<UUID, String> names) {
    return new PriceListView(
        l.getUid(),
        l.getName(),
        l.getCustomerType(),
        l.getCustomerUid(),
        l.getCustomerName(),
        l.getAdjustmentPercent(),
        l.getValidFrom(),
        l.getValidTo(),
        l.isActive(),
        l.getItems().stream()
            .map(
                i ->
                    new ItemView(
                        i.getProductUid(),
                        names.get(i.getProductUid()),
                        i.getMinQuantity(),
                        i.getPrice()))
            .toList());
  }

  private static PromotionView view(
      Promotion p, Map<UUID, String> productNames, Map<UUID, String> categoryNames) {
    return new PromotionView(
        p.getUid(),
        p.getName(),
        p.getPercentOff(),
        p.getProductUid(),
        p.getProductUid() == null ? null : productNames.get(p.getProductUid()),
        p.getCategoryUid(),
        p.getCategoryUid() == null ? null : categoryNames.get(p.getCategoryUid()),
        p.getBranchUid(),
        p.getValidFrom(),
        p.getValidTo(),
        p.isActive());
  }
}
