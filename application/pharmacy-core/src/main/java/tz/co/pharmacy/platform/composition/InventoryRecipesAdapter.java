package tz.co.pharmacy.platform.composition;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.catalogue.ports.inbound.PackagingService;
import tz.co.pharmacy.modules.inventory.ports.outbound.Recipes;

/** Binds inventory's recipes (pack links and formulas) to the catalogue's packaging. */
@Component
@RequiredArgsConstructor
class InventoryRecipesAdapter implements Recipes {

  private final PackagingService packaging;

  @Override
  public Optional<PackLink> packOf(UUID unitProductUid) {
    return packaging
        .packOf(unitProductUid)
        .map(
            link ->
                new PackLink(link.packProductUid(), link.unitProductUid(), link.unitsPerPack()));
  }

  @Override
  public List<PackLink> linksOfPack(UUID packProductUid) {
    return packaging.packLinks().stream()
        .filter(link -> link.packProductUid().equals(packProductUid))
        .map(
            link -> new PackLink(link.packProductUid(), link.unitProductUid(), link.unitsPerPack()))
        .toList();
  }

  @Override
  public List<Ingredient> formula(UUID productUid) {
    return packaging.formula(productUid).stream()
        .map(item -> new Ingredient(item.ingredientUid(), item.ingredientName(), item.quantity()))
        .toList();
  }
}
