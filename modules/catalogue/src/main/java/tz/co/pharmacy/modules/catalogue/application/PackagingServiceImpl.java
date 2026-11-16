package tz.co.pharmacy.modules.catalogue.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.catalogue.domain.FormulaItem;
import tz.co.pharmacy.modules.catalogue.domain.PackLink;
import tz.co.pharmacy.modules.catalogue.domain.Product;
import tz.co.pharmacy.modules.catalogue.persistence.FormulaItemRepository;
import tz.co.pharmacy.modules.catalogue.persistence.PackLinkRepository;
import tz.co.pharmacy.modules.catalogue.persistence.ProductRepository;
import tz.co.pharmacy.modules.catalogue.ports.inbound.PackagingService;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.Refusal;

@Service
@RequiredArgsConstructor
class PackagingServiceImpl implements PackagingService {

  private final PackLinkRepository links;
  private final FormulaItemRepository formulas;
  private final ProductRepository products;

  @Override
  @Transactional(readOnly = true)
  public List<PackLinkView> packLinks() {
    List<PackLink> all = links.findAllByOrderByIdAsc();
    Map<UUID, String> names =
        names(all.stream().flatMap(l -> Stream.of(l.getPackProductUid(), l.getUnitProductUid())));
    return all.stream().map(l -> view(l, names)).toList();
  }

  @Override
  @Transactional
  public PackLinkView savePackLink(UUID packProductUid, UUID unitProductUid, Integer unitsPerPack) {
    UUID pack = Checks.required(packProductUid, "packProductUid");
    UUID unit = Checks.required(unitProductUid, "unitProductUid");
    int units = unitsPerPack == null ? 0 : unitsPerPack;
    if (units < 2) {
      throw Refusal.invalid(
          "invalid_units_per_pack", "unitsPerPack must be at least 2.", "unitsPerPack");
    }
    if (pack.equals(unit)) {
      throw Refusal.invalid(
          "same_product", "A pack and its unit must be different products.", "unitProductUid");
    }
    Map<UUID, String> names = names(Stream.of(pack, unit));
    if (!names.containsKey(pack) || !names.containsKey(unit)) {
      throw Refusal.invalid("product_not_found", "No such product.", "packProductUid");
    }
    PackLink link =
        links
            .findByUnitProductUid(unit)
            .map(
                existing -> {
                  existing.change(pack, units);
                  return existing;
                })
            .orElseGet(() -> links.save(new PackLink(pack, unit, units)));
    return view(link, names);
  }

  @Override
  @Transactional
  public void deletePackLink(UUID unitProductUid) {
    links.findByUnitProductUid(unitProductUid).ifPresent(links::delete);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<PackLinkView> packOf(UUID unitProductUid) {
    return links
        .findByUnitProductUid(unitProductUid)
        .map(l -> view(l, names(Stream.of(l.getPackProductUid(), l.getUnitProductUid()))));
  }

  @Override
  @Transactional(readOnly = true)
  public Map<UUID, PackLinkView> packsOf(java.util.Collection<UUID> unitProductUids) {
    if (unitProductUids == null || unitProductUids.isEmpty()) {
      return Map.of();
    }
    Map<UUID, PackLinkView> packs = new java.util.HashMap<>();
    for (PackLink l : links.findAllByUnitProductUidIn(unitProductUids)) {
      packs.put(l.getUnitProductUid(), view(l, Map.of()));
    }
    return packs;
  }

  @Override
  @Transactional(readOnly = true)
  public List<FormulaItemView> formula(UUID productUid) {
    List<FormulaItem> items = formulas.findByProductUidOrderByIdAsc(productUid);
    Map<UUID, String> names = names(items.stream().map(FormulaItem::getIngredientUid));
    return items.stream()
        .map(
            i ->
                new FormulaItemView(
                    i.getIngredientUid(), names.get(i.getIngredientUid()), i.getQuantity()))
        .toList();
  }

  @Override
  @Transactional
  public List<FormulaItemView> saveFormula(UUID productUid, List<FormulaItemCommand> items) {
    Checks.required(productUid, "productUid");
    if (products.findByUid(productUid).isEmpty()) {
      throw Refusal.notFound("product_not_found", "No such product.");
    }
    List<FormulaItemCommand> commands = items == null ? List.of() : items;
    Set<UUID> seen = new HashSet<>();
    List<FormulaItem> replacement = new ArrayList<>();
    for (int i = 0; i < commands.size(); i++) {
      FormulaItemCommand c = commands.get(i);
      String field = "items[" + i + "]";
      UUID ingredient = Checks.required(c.ingredientUid(), field + ".ingredientUid");
      BigDecimal quantity = Checks.required(c.quantity(), field + ".quantity");
      if (quantity.signum() <= 0) {
        throw Refusal.invalid("invalid_quantity", "quantity must be more than zero.", field);
      }
      if (ingredient.equals(productUid)) {
        throw Refusal.invalid("same_product", "A product cannot be its own ingredient.", field);
      }
      if (!seen.add(ingredient)) {
        throw Refusal.invalid("duplicate_item", "An ingredient appears twice.", field);
      }
      replacement.add(new FormulaItem(productUid, ingredient, quantity));
    }
    if (names(seen.stream()).size() != seen.size()) {
      throw Refusal.invalid("product_not_found", "No such ingredient.", "items");
    }
    formulas.deleteByProductUid(productUid);
    formulas.flush();
    formulas.saveAll(replacement);
    return formula(productUid);
  }

  private Map<UUID, String> names(Stream<UUID> uids) {
    List<UUID> list = uids.distinct().toList();
    if (list.isEmpty()) {
      return Map.of();
    }
    return products.findByUidIn(list).stream()
        .collect(Collectors.toMap(Product::getUid, Product::getName));
  }

  private static PackLinkView view(PackLink l, Map<UUID, String> names) {
    return new PackLinkView(
        l.getPackProductUid(),
        names.get(l.getPackProductUid()),
        l.getUnitProductUid(),
        names.get(l.getUnitProductUid()),
        l.getUnitsPerPack());
  }
}
