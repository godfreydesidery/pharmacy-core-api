package tz.co.pharmacy.modules.inventory.ports.outbound;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The catalogue's recipes a transformation must follow (FRD-2): a pack link (a box opens into so
 * many strips; so many bulk units fill one own pack) and a compounded product's formula.
 */
public interface Recipes {

  /** {@code unitsPerPack} of the unit product make one of the pack product. */
  record PackLink(UUID packProductUid, UUID unitProductUid, int unitsPerPack) {}

  /** How much of an ingredient one unit of a compounded product takes. */
  record Ingredient(UUID productUid, String name, BigDecimal quantity) {}

  /** The pack the unit product comes in, if any. */
  Optional<PackLink> packOf(UUID unitProductUid);

  /** The links whose pack is this product. */
  List<PackLink> linksOfPack(UUID packProductUid);

  /** The product's formula; empty when it has none. */
  List<Ingredient> formula(UUID productUid);
}
