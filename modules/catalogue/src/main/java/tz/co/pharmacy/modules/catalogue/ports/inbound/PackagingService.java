package tz.co.pharmacy.modules.catalogue.ports.inbound;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Pack links (a box opens into its strips) and formulas (what a compounded preparation is made of).
 */
public interface PackagingService {

  List<PackLinkView> packLinks();

  /** Links a unit product to the pack it comes in; one pack per unit product. */
  PackLinkView savePackLink(UUID packProductUid, UUID unitProductUid, Integer unitsPerPack);

  void deletePackLink(UUID unitProductUid);

  /** The pack a unit product is broken from, if any. */
  Optional<PackLinkView> packOf(UUID unitProductUid);

  /** The packs the given unit products are broken from, by unit product (names not resolved). */
  java.util.Map<UUID, PackLinkView> packsOf(java.util.Collection<UUID> unitProductUids);

  List<FormulaItemView> formula(UUID productUid);

  /** Replaces a product's formula; an empty list removes it. */
  List<FormulaItemView> saveFormula(UUID productUid, List<FormulaItemCommand> items);

  record PackLinkView(
      UUID packProductUid,
      String packProductName,
      UUID unitProductUid,
      String unitProductName,
      int unitsPerPack) {}

  record FormulaItemCommand(UUID ingredientUid, BigDecimal quantity) {}

  record FormulaItemView(UUID ingredientUid, String ingredientName, BigDecimal quantity) {}
}
