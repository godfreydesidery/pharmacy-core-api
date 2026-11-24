package tz.co.pharmacy.modules.catalogue.application;

import java.math.BigDecimal;
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
  private final PriceHistory history;
  private final tz.co.pharmacy.modules.catalogue.persistence.PriceChangeRepository changes;
  private final CatalogueServiceImpl catalogue;
  private final tz.co.pharmacy.modules.catalogue.ports.outbound.PricingSettings settings;
  private final tz.co.pharmacy.platform.approval.Approvals approvals;
  private final tz.co.pharmacy.modules.catalogue.persistence.PriceListItemRepository listItems;

  @Override
  @Transactional(readOnly = true)
  public List<PriceListView> priceLists() {
    // PERF-11: the plans without their items, with how many each holds; a plan's items are read a
    // page at a time (planItems) or for one plan (priceList).
    Map<Long, long[]> counts = counts();
    return lists.plansByName().stream()
        .map(l -> header(l, counts.getOrDefault(l.getId(), new long[2])[0]))
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<PriceListView> priceList(UUID uid) {
    return lists
        .findByUid(uid)
        .map(l -> view(l, names(l.getItems().stream().map(PriceListItem::getProductUid))));
  }

  /** One product's prices in every plan, by plan id (PERF-11: not every plan's every item). */
  private Map<Long, List<PriceListItem>> itemsOfProduct(UUID productUid) {
    return listItems.itemsOfProduct(productUid).stream()
        .collect(Collectors.groupingBy(i -> i.getPriceList().getId()));
  }

  /** Per plan id: its prices and the products they are for. */
  private Map<Long, long[]> counts() {
    Map<Long, long[]> counts = new HashMap<>();
    for (Object[] row : listItems.counts()) {
      counts.put(
          (Long) row[0], new long[] {((Number) row[1]).longValue(), ((Number) row[2]).longValue()});
    }
    return counts;
  }

  @Override
  @Transactional
  public PriceListView savePriceList(UUID uid, PriceListCommand c) {
    return save(uid, c, tz.co.pharmacy.modules.catalogue.domain.PriceChange.Source.MANUAL);
  }

  @Override
  @Transactional
  public PriceListView importPriceList(UUID uid, PriceListCommand c) {
    return save(uid, c, tz.co.pharmacy.modules.catalogue.domain.PriceChange.Source.IMPORT);
  }

  private PriceListView save(
      UUID uid,
      PriceListCommand c,
      tz.co.pharmacy.modules.catalogue.domain.PriceChange.Source source) {
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
    if (c.payerUid() != null && (type != null || c.customerUid() != null)) {
      throw Refusal.invalid(
          "price_list_target",
          "An insurer's plan is for the insurer's members, not for a customer type or a customer.",
          "payerUid");
    }
    if (c.validFrom() != null && c.validTo() != null && c.validTo().isBefore(c.validFrom())) {
      throw Refusal.invalid("invalid_period", "validTo is before validFrom.", "validTo");
    }
    BigDecimal adjustment = c.adjustmentPercent();
    if (adjustment != null && adjustment.compareTo(new BigDecimal("-100")) <= 0) {
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
    list.forPayer(c.payerUid(), c.payerName());
    if (uid != null && (c.items() == null || c.items().isEmpty())) {
      // A save that names no items keeps the plan's own: a client that listed the plans (which
      // come without their items, PERF-11) cannot empty a plan by saving its name or dates. A
      // plan's prices are removed one by one (DELETE /pricing-plans/{plan}/prices/{product}).
      PriceList saved = lists.saveAndFlush(list);
      return view(saved, names(saved.getItems().stream().map(PriceListItem::getProductUid)));
    }
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
    Map<String, BigDecimal> before = new HashMap<>();
    list.getItems()
        .forEach(i -> before.put(i.getProductUid() + ":" + i.getMinQuantity(), i.getPrice()));
    list.items(items);
    PriceList saved = lists.saveAndFlush(list);
    Map<String, BigDecimal> after = new HashMap<>();
    saved
        .getItems()
        .forEach(i -> after.put(i.getProductUid() + ":" + i.getMinQuantity(), i.getPrice()));
    Set<String> keys = new HashSet<>(before.keySet());
    keys.addAll(after.keySet());
    Map<UUID, String> changedNames =
        names(keys.stream().map(k -> UUID.fromString(k.substring(0, k.indexOf(':')))).distinct());
    for (String key : keys) {
      UUID product = UUID.fromString(key.substring(0, key.indexOf(':')));
      history.record(
          product,
          changedNames.getOrDefault(product, "?"),
          saved.getUid(),
          saved.getName(),
          Integer.parseInt(key.substring(key.indexOf(':') + 1)),
          before.get(key),
          after.get(key),
          source);
    }
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
    boolean active = c.active() == null || c.active();
    BigDecimal max = settings.maxDiscountPercent();
    if (active && max != null && percent.compareTo(max) > 0) {
      // FRD-8: a promotion deeper than the till's maximum discount is a manager's decision.
      approvals.require(
          tz.co.pharmacy.platform.approval.ApprovalActions.DEEP_PROMOTION, percent, name);
    }
    promotion.change(
        name, percent, c.productUid(), c.categoryUid(), c.branchUid(), from, to, active);
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
  public PromotionView switchOffPromotion(UUID uid) {
    Promotion promotion =
        promotions
            .findByUid(uid)
            .orElseThrow(() -> Refusal.notFound("promotion_not_found", "No such promotion."));
    promotion.switchOff();
    return view(
        promotion,
        names(
            promotion.getProductUid() == null
                ? java.util.stream.Stream.empty()
                : java.util.stream.Stream.of(promotion.getProductUid())),
        categories.findAllByOrderByNameAsc().stream()
            .collect(Collectors.toMap(Category::getUid, Category::getName, (a, b) -> a)));
  }

  @Override
  @Transactional(readOnly = true)
  public List<PricedLine> price(
      UUID branchUid,
      String customerType,
      UUID customerUid,
      List<PriceRequest> lines,
      LocalDate on) {
    return price(branchUid, customerType, customerUid, null, lines, on);
  }

  @Override
  @Transactional(readOnly = true)
  public List<PricedLine> price(
      UUID branchUid,
      String customerType,
      UUID customerUid,
      PlanChoice plan,
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
    Optional<PriceList> list =
        plan == null
            ? listFor(customerUid, customerType, day)
            : plan.planUid() == null ? Optional.empty() : Optional.of(chosen(plan.planUid(), day));
    // PERF-11: only these products' prices in the plan, never the whole plan.
    Map<UUID, List<PriceListItem>> planPrices =
        list.isEmpty() || byUid.isEmpty()
            ? Map.of()
            : listItems.itemsOf(list.get(), byUid.keySet()).stream()
                .collect(Collectors.groupingBy(PriceListItem::getProductUid));
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
      boolean unpriced = false;
      if (branch.containsKey(product.getUid())) {
        price = branch.get(product.getUid());
        source = "BRANCH";
      }
      if (list.isPresent()) {
        PriceList l = list.get();
        Optional<PriceListItem> item =
            planPrices.getOrDefault(product.getUid(), List.of()).stream()
                .filter(i -> i.getMinQuantity() <= quantity)
                .max(Comparator.comparingInt(PriceListItem::getMinQuantity));
        if (item.isPresent()) {
          price = item.get().getPrice();
          source = "LIST";
          label = l.getName();
        } else if (l.getAdjustmentPercent() != null) {
          price = adjust(price, l.getAdjustmentPercent());
          source = "LIST";
          label = l.getName();
        } else {
          // The plan has no price and no rule for it: refused at checkout.
          source = "LIST";
          label = l.getName();
          unpriced = true;
        }
      }
      Promotion best = null;
      UUID promotionUid = null;
      BigDecimal bestPrice = price;
      // A plan's price stands as it is: promotions only lower Normal Sale prices (no stacking).
      for (Promotion p : list.isPresent() ? List.<Promotion>of() : running) {
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
        promotionUid = best.getUid();
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
              cost != null && price.compareTo(cost) < 0,
              unpriced,
              promotionUid));
    }
    return out;
  }

  /** The plan chosen for a sale: active and in its dates, or 422 price_plan_not_found. */
  private PriceList chosen(UUID planUid, LocalDate day) {
    return lists
        .planByUid(planUid)
        .filter(l -> l.validOn(day))
        .orElseThrow(
            () ->
                Refusal.invalid(
                    "price_plan_not_found",
                    "That pricing plan does not exist, is switched off or is out of its dates.",
                    "pricePlan"));
  }

  @Override
  @Transactional(readOnly = true)
  public ResolvedPlan resolvePlan(
      String requested, UUID customerUid, String customerType, LocalDate on) {
    return resolvePlan(requested, customerUid, customerType, null, on);
  }

  @Override
  @Transactional(readOnly = true)
  public ResolvedPlan resolvePlan(
      String requested, UUID customerUid, String customerType, UUID payerUid, LocalDate on) {
    LocalDate day = on == null ? calendar.today() : on;
    PlanView own = defaultPlan(customerUid, customerType, payerUid, day);
    PlanChoice ownChoice = new PlanChoice(own.uid());
    if (requested == null || requested.isBlank()) {
      return new ResolvedPlan(ownChoice, own, own);
    }
    UUID wanted =
        "NORMAL".equalsIgnoreCase(requested.trim())
            ? null
            : tz.co.pharmacy.platform.kernel.Uids.parse(requested.trim(), "pricePlan");
    if (java.util.Objects.equals(wanted, own.uid())) {
      return new ResolvedPlan(ownChoice, own, own);
    }
    if (!tz.co.pharmacy.platform.kernel.ExecutionContext.permits(CHANGE_PLAN_PERMISSION)) {
      throw Refusal.forbidden(
          "price_plan_not_allowed", "You may not change the pricing plan of a sale.");
    }
    PlanView plan = wanted == null ? PlanView.normal() : planView(chosen(wanted, day));
    return new ResolvedPlan(new PlanChoice(wanted), plan, own);
  }

  @Override
  @Transactional(readOnly = true)
  public PlanView defaultPlan(UUID customerUid, String customerType, UUID payerUid, LocalDate on) {
    LocalDate day = on == null ? calendar.today() : on;
    if (payerUid != null) {
      Optional<PriceList> insurer =
          lists.plansOfPayer(payerUid).stream()
              .filter(l -> l.validOn(day))
              .max(Comparator.comparing(PriceList::getCreatedAt));
      if (insurer.isPresent()) {
        return planView(insurer.get());
      }
    }
    return defaultPlan(customerUid, customerType, day);
  }

  @Override
  @Transactional(readOnly = true)
  public PlanView defaultPlan(UUID customerUid, String customerType, LocalDate on) {
    return listFor(customerUid, customerType, on == null ? calendar.today() : on)
        .map(PricingServiceImpl::planView)
        .orElse(PlanView.normal());
  }

  @Override
  @Transactional(readOnly = true)
  public List<PlanView> plans() {
    List<PlanView> out = new ArrayList<>();
    out.add(PlanView.normal());
    lists.plansByName().stream()
        .filter(PriceList::isActive)
        .map(PricingServiceImpl::planView)
        .forEach(out::add);
    return out;
  }

  private static PlanView planView(PriceList l) {
    return new PlanView(
        l.getUid(),
        l.getName(),
        l.getAdjustmentPercent(),
        l.getCustomerType(),
        l.getCustomerUid(),
        l.getCustomerName(),
        l.getPayerUid(),
        l.getPayerName());
  }

  @Override
  @Transactional
  public BulkResult bulk(BulkCommand c, boolean apply) {
    BigDecimal percent = Checks.required(c.percent(), "percent");
    if (percent.compareTo(new BigDecimal("-100")) <= 0) {
      throw Refusal.invalid("invalid_adjustment", "percent must be above -100.", "percent");
    }
    BigDecimal step = c.roundTo() == null ? new BigDecimal("50") : c.roundTo();
    if (step.signum() < 0) {
      throw Refusal.invalid("invalid_rounding", "roundTo cannot be negative.", "roundTo");
    }
    java.math.RoundingMode mode =
        switch (c.rounding() == null
            ? "NEAREST"
            : c.rounding().trim().toUpperCase(java.util.Locale.ROOT)) {
          case "UP" -> java.math.RoundingMode.CEILING;
          case "DOWN" -> java.math.RoundingMode.FLOOR;
          case "NEAREST" -> java.math.RoundingMode.HALF_UP;
          default ->
              throw Refusal.invalid(
                  "invalid_rounding", "rounding is NEAREST, UP or DOWN.", "rounding");
        };
    PriceList list =
        c.planUid() == null
            ? null
            : lists
                .findByUid(c.planUid())
                .orElseThrow(
                    () -> Refusal.notFound("price_list_not_found", "No such pricing plan."));
    Set<UUID> tree = c.categoryUid() == null ? null : descendants(c.categoryUid());
    Map<UUID, BigDecimal> current = new HashMap<>();
    if (list != null) {
      list.getItems().stream()
          .filter(i -> i.getMinQuantity() == 1)
          .forEach(i -> current.put(i.getProductUid(), i.getPrice()));
    }
    boolean onlyMissing = list != null && Boolean.TRUE.equals(c.onlyMissing());
    List<BulkLine> lines = new ArrayList<>();
    for (Product p : products.findAll(org.springframework.data.domain.Sort.by("name"))) {
      if (!p.isActive()
          || (tree != null && (p.getCategory() == null || !tree.contains(p.getCategory().getUid())))
          || (onlyMissing && current.containsKey(p.getUid()))) {
        continue;
      }
      BigDecimal target = p.getSellingPrice().multiply(BigDecimal.ONE.add(percent.divide(HUNDRED)));
      if (step.signum() > 0) {
        target = target.divide(step, 0, mode).multiply(step);
      }
      target = Amounts.money(target);
      BigDecimal old = list == null ? p.getSellingPrice() : current.get(p.getUid());
      if (old != null && old.compareTo(target) == 0) {
        continue;
      }
      lines.add(new BulkLine(p.getUid(), p.getSku(), p.getName(), old, target));
    }
    String planName = list == null ? PriceHistory.NORMAL_SALE : list.getName();
    if (!apply) {
      return new BulkResult(planName, lines, false);
    }
    Map<UUID, BigDecimal> newPrices = new HashMap<>();
    lines.forEach(l -> newPrices.put(l.productUid(), l.newPrice()));
    if (list == null) {
      for (Product p : products.findByUidIn(newPrices.keySet())) {
        p.reprice(newPrices.get(p.getUid()));
        catalogue.publish(p);
      }
    } else {
      List<PriceListItem> replacement = new ArrayList<>();
      for (PriceListItem i : list.getItems()) {
        if (!(i.getMinQuantity() == 1 && newPrices.containsKey(i.getProductUid()))) {
          replacement.add(new PriceListItem(i.getProductUid(), i.getMinQuantity(), i.getPrice()));
        }
      }
      newPrices.forEach((product, price) -> replacement.add(new PriceListItem(product, 1, price)));
      list.items(replacement);
      lists.save(list);
    }
    for (BulkLine l : lines) {
      history.record(
          l.productUid(),
          l.name(),
          list == null ? null : list.getUid(),
          list == null ? null : list.getName(),
          1,
          l.oldPrice(),
          l.newPrice(),
          tz.co.pharmacy.modules.catalogue.domain.PriceChange.Source.BULK);
    }
    return new BulkResult(planName, lines, true);
  }

  @Override
  @Transactional(readOnly = true)
  public tz.co.pharmacy.platform.kernel.Page<GridRow> grid(
      String q,
      UUID categoryUid,
      boolean missingOnly,
      tz.co.pharmacy.platform.kernel.PageQuery query) {
    List<PriceList> active =
        lists.findAllByOrderByNameAsc().stream().filter(PriceList::isActive).toList();
    Map<UUID, Map<UUID, BigDecimal>> byProduct = new HashMap<>();
    for (PriceList l : active) {
      for (PriceListItem i : l.getItems()) {
        if (i.getMinQuantity() == 1) {
          byProduct
              .computeIfAbsent(i.getProductUid(), k -> new java.util.LinkedHashMap<>())
              .put(l.getUid(), i.getPrice());
        }
      }
    }
    List<PriceList> blocking =
        active.stream().filter(l -> l.getAdjustmentPercent() == null).toList();
    String needle = q == null || q.isBlank() ? null : q.trim().toLowerCase(java.util.Locale.ROOT);
    Set<UUID> tree = categoryUid == null ? null : descendants(categoryUid);
    List<GridRow> rows =
        products.findAll(org.springframework.data.domain.Sort.by("name")).stream()
            .filter(Product::isActive)
            .filter(
                p ->
                    tree == null
                        || (p.getCategory() != null && tree.contains(p.getCategory().getUid())))
            .filter(
                p ->
                    needle == null
                        || p.getName().toLowerCase(java.util.Locale.ROOT).contains(needle)
                        || (p.getSku() != null
                            && p.getSku().toLowerCase(java.util.Locale.ROOT).contains(needle))
                        || (p.getGtin() != null && p.getGtin().contains(needle)))
            .filter(
                p ->
                    !missingOnly
                        || blocking.stream()
                            .anyMatch(
                                l ->
                                    !byProduct
                                        .getOrDefault(p.getUid(), Map.of())
                                        .containsKey(l.getUid())))
            .map(p -> gridRow(p, byProduct.getOrDefault(p.getUid(), Map.of())))
            .toList();
    int from = (int) Math.min(query.offset(), rows.size());
    int to = (int) Math.min(from + (long) query.limit() + 1, rows.size());
    return tz.co.pharmacy.platform.kernel.Page.offset(
        rows.subList(from, to), query, () -> rows.size());
  }

  private static GridRow gridRow(Product p, Map<UUID, BigDecimal> prices) {
    return new GridRow(
        p.getUid(),
        p.getSku(),
        p.getName(),
        p.getGtin(),
        p.getCategory() == null ? null : p.getCategory().getName(),
        p.getCostPrice(),
        p.getSellingPrice(),
        Map.copyOf(prices));
  }

  @Override
  @Transactional
  public GridRow setPrice(UUID productUid, UUID planUid, BigDecimal price) {
    return setPrice(productUid, planUid, 1, price);
  }

  @Override
  @Transactional
  public GridRow setPrice(UUID productUid, UUID planUid, int minQuantity, BigDecimal price) {
    int min = Math.max(1, minQuantity);
    Product product =
        products
            .findByUid(productUid)
            .orElseThrow(() -> Refusal.notFound("product_not_found", "No such product."));
    if (price != null && price.signum() < 0) {
      throw Refusal.invalid("invalid_price", "A price cannot be negative.", "price");
    }
    if (planUid == null) {
      if (price == null) {
        throw Refusal.invalid("invalid_price", "Every medicine has a Normal Sale price.", "price");
      }
      BigDecimal before = product.getSellingPrice();
      product.reprice(Amounts.money(price));
      history.record(
          productUid,
          product.getName(),
          null,
          null,
          1,
          before,
          product.getSellingPrice(),
          tz.co.pharmacy.modules.catalogue.domain.PriceChange.Source.MANUAL);
      products.flush();
      catalogue.publish(product);
    } else {
      PriceList list =
          lists
              .findByUid(planUid)
              .orElseThrow(() -> Refusal.notFound("price_list_not_found", "No such pricing plan."));
      BigDecimal before =
          list.getItems().stream()
              .filter(i -> i.getProductUid().equals(productUid) && i.getMinQuantity() == min)
              .map(PriceListItem::getPrice)
              .findFirst()
              .orElse(null);
      List<PriceListItem> replacement = new ArrayList<>();
      for (PriceListItem i : list.getItems()) {
        if (!(i.getProductUid().equals(productUid) && i.getMinQuantity() == min)) {
          replacement.add(new PriceListItem(i.getProductUid(), i.getMinQuantity(), i.getPrice()));
        }
      }
      if (price != null) {
        replacement.add(new PriceListItem(productUid, min, Amounts.money(price)));
      }
      list.items(replacement);
      lists.save(list);
      history.record(
          productUid,
          product.getName(),
          list.getUid(),
          list.getName(),
          min,
          before,
          price == null ? null : Amounts.money(price),
          tz.co.pharmacy.modules.catalogue.domain.PriceChange.Source.MANUAL);
    }
    Map<UUID, BigDecimal> prices = new java.util.LinkedHashMap<>();
    Map<Long, List<PriceListItem>> mine = itemsOfProduct(productUid);
    for (PriceList l : lists.plansByName()) {
      if (l.isActive()) {
        mine.getOrDefault(l.getId(), List.of()).stream()
            .filter(i -> i.getMinQuantity() == 1)
            .findFirst()
            .ifPresent(i -> prices.put(l.getUid(), i.getPrice()));
      }
    }
    return gridRow(product, prices);
  }

  @Override
  @Transactional(readOnly = true)
  public List<PlanCard> planCards() {
    long medicines = products.findAll().stream().filter(Product::isActive).count();
    List<PlanCard> cards = new ArrayList<>();
    cards.add(
        new PlanCard(
            null,
            PriceHistory.NORMAL_SALE,
            null,
            null,
            null,
            null,
            null,
            BigDecimal.ZERO,
            true,
            null,
            null,
            medicines,
            null));
    Map<Long, long[]> counts = counts();
    for (PriceList l : lists.plansByName()) {
      cards.add(
          new PlanCard(
              l.getUid(),
              l.getName(),
              l.getCustomerType(),
              l.getCustomerUid(),
              l.getCustomerName(),
              l.getPayerUid(),
              l.getPayerName(),
              l.getAdjustmentPercent(),
              l.isActive(),
              l.getValidFrom(),
              l.getValidTo(),
              counts.getOrDefault(l.getId(), new long[2])[1],
              l.getVersion()));
    }
    return cards;
  }

  @Override
  @Transactional(readOnly = true)
  public tz.co.pharmacy.platform.kernel.Page<PlanItemRow> planItems(
      UUID planUid,
      String q,
      UUID categoryUid,
      boolean notPriced,
      tz.co.pharmacy.platform.kernel.PageQuery query) {
    String needle = q == null || q.isBlank() ? null : q.trim().toLowerCase(java.util.Locale.ROOT);
    Set<UUID> tree = categoryUid == null ? null : descendants(categoryUid);
    java.util.function.Predicate<Product> wanted =
        p ->
            p.isActive()
                && (tree == null
                    || (p.getCategory() != null && tree.contains(p.getCategory().getUid())))
                && (needle == null
                    || p.getName().toLowerCase(java.util.Locale.ROOT).contains(needle)
                    || (p.getSku() != null
                        && p.getSku().toLowerCase(java.util.Locale.ROOT).contains(needle))
                    || (p.getGtin() != null && p.getGtin().contains(needle)));
    List<PlanItemRow> rows = new ArrayList<>();
    if (planUid == null) {
      products.findAll(org.springframework.data.domain.Sort.by("name")).stream()
          .filter(wanted)
          .forEach(p -> rows.add(itemRow(p, 1, p.getSellingPrice())));
    } else {
      PriceList list =
          lists
              .findByUid(planUid)
              .orElseThrow(() -> Refusal.notFound("price_list_not_found", "No such pricing plan."));
      Set<UUID> priced = new HashSet<>();
      list.getItems().forEach(i -> priced.add(i.getProductUid()));
      if (notPriced) {
        products.findAll(org.springframework.data.domain.Sort.by("name")).stream()
            .filter(wanted)
            .filter(p -> !priced.contains(p.getUid()))
            .forEach(p -> rows.add(itemRow(p, 1, null)));
      } else {
        Map<UUID, Product> byUid =
            products.findByUidIn(priced).stream()
                .collect(Collectors.toMap(Product::getUid, Function.identity()));
        list.getItems().stream()
            .filter(
                i ->
                    byUid.containsKey(i.getProductUid())
                        && wanted.test(byUid.get(i.getProductUid())))
            .sorted(
                Comparator.comparing((PriceListItem i) -> byUid.get(i.getProductUid()).getName())
                    .thenComparingInt(PriceListItem::getMinQuantity))
            .forEach(
                i ->
                    rows.add(
                        itemRow(byUid.get(i.getProductUid()), i.getMinQuantity(), i.getPrice())));
      }
    }
    int from = (int) Math.min(query.offset(), rows.size());
    int to = (int) Math.min(from + (long) query.limit() + 1, rows.size());
    return tz.co.pharmacy.platform.kernel.Page.offset(
        rows.subList(from, to), query, () -> rows.size());
  }

  private static PlanItemRow itemRow(Product p, int minQuantity, BigDecimal price) {
    return new PlanItemRow(
        p.getUid(),
        p.getSku(),
        p.getName(),
        p.getGtin(),
        p.getCategory() == null ? null : p.getCategory().getName(),
        p.getCostPrice(),
        p.getSellingPrice(),
        minQuantity,
        price);
  }

  @Override
  @Transactional(readOnly = true)
  public List<PlanPriceView> planPrices(UUID productUid) {
    Product product =
        products
            .findByUid(productUid)
            .orElseThrow(() -> Refusal.notFound("product_not_found", "No such product."));
    List<PlanPriceView> out = new ArrayList<>();
    out.add(
        new PlanPriceView(
            null, PriceHistory.NORMAL_SALE, product.getSellingPrice(), "PRICE", List.of(), true));
    Map<Long, List<PriceListItem>> ofProduct = itemsOfProduct(productUid);
    for (PriceList l : lists.plansByName()) {
      List<PriceListItem> mine =
          ofProduct.getOrDefault(l.getId(), List.of()).stream()
              .sorted(Comparator.comparingInt(PriceListItem::getMinQuantity))
              .toList();
      BigDecimal one =
          mine.stream()
              .filter(i -> i.getMinQuantity() == 1)
              .map(PriceListItem::getPrice)
              .findFirst()
              .orElse(null);
      String basis;
      if (one != null) {
        basis = "PRICE";
      } else if (l.getAdjustmentPercent() != null) {
        basis = "RULE";
        one = Amounts.money(adjust(product.getSellingPrice(), l.getAdjustmentPercent()));
      } else {
        basis = "BLOCKED";
      }
      out.add(
          new PlanPriceView(
              l.getUid(),
              l.getName(),
              one,
              basis,
              mine.stream()
                  .filter(i -> i.getMinQuantity() > 1)
                  .map(i -> new PriceBreak(i.getMinQuantity(), i.getPrice()))
                  .toList(),
              l.isActive()));
    }
    return out;
  }

  @Override
  @Transactional(readOnly = true)
  public List<PriceChangeView> history(UUID productUid) {
    return changes.findTop200ByProductUidOrderByCreatedAtDescIdDesc(productUid).stream()
        .map(
            c ->
                new PriceChangeView(
                    c.getPriceListUid(),
                    c.getPlanName(),
                    c.getMinQuantity(),
                    c.getOldPrice(),
                    c.getNewPrice(),
                    c.getSource().name(),
                    c.getCreatedBy(),
                    c.getCreatedAt()))
        .toList();
  }

  /** The customer's own list, else their type's; the newest valid one if several. */
  private Optional<PriceList> listFor(UUID customerUid, String customerType, LocalDate day) {
    if (customerUid != null) {
      Optional<PriceList> own =
          lists.plansOfCustomer(customerUid).stream()
              .filter(l -> l.validOn(day))
              .max(Comparator.comparing(PriceList::getCreatedAt));
      if (own.isPresent()) {
        return own;
      }
    }
    if (customerType == null || customerType.isBlank()) {
      return Optional.empty();
    }
    return lists.plansOfType(customerType).stream()
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

  /**
   * {@code price} moved by {@code percent} (negative for less), rounded to the pharmacy's minor
   * units (cents, or whole shillings in a UGX pharmacy).
   */
  private static BigDecimal adjust(BigDecimal price, BigDecimal percent) {
    return Amounts.money(price.multiply(HUNDRED.add(percent)).movePointLeft(2))
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

  /** A plan without its items (null), with how many prices it holds. */
  private static PriceListView header(PriceList l, long itemCount) {
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
        null,
        l.getPayerUid(),
        l.getPayerName(),
        (int) itemCount,
        l.getVersion());
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
            .toList(),
        l.getPayerUid(),
        l.getPayerName(),
        l.getItems().size(),
        l.getVersion());
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
