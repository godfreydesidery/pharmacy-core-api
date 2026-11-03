package tz.co.pharmacy.platform.composition;

import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.catalogue.ports.inbound.CatalogueService;
import tz.co.pharmacy.modules.inventory.ports.inbound.ProductSnapshot;
import tz.co.pharmacy.modules.inventory.ports.outbound.ProductCatalogue;

/** Binds inventory's read of a catalogue product, for a product it has no snapshot of yet. */
@Component
@RequiredArgsConstructor
class InventoryCatalogueAdapter implements ProductCatalogue {

  private final CatalogueService catalogueService;

  @Override
  public Optional<ProductSnapshot> product(UUID productUid) {
    return catalogueService
        .product(productUid)
        .map(
            product ->
                new ProductSnapshot(
                    product.uid(),
                    product.name(),
                    product.genericName(),
                    product.sku(),
                    product.dosageForm() == null ? null : product.dosageForm().name(),
                    product.strength(),
                    product.categoryUid(),
                    product.categoryName(),
                    product.reorderLevel(),
                    product.sellingPrice(),
                    product.active(),
                    product.productType() == null ? null : product.productType().name(),
                    product.trackingMode() == null ? null : product.trackingMode().name(),
                    product.unitOfMeasure() == null ? null : product.unitOfMeasure().name()));
  }
}
