package tz.co.pharmacy.platform.composition;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import tz.co.pharmacy.modules.catalogue.events.ProductChanged;
import tz.co.pharmacy.modules.inventory.ports.inbound.ProductSnapshot;
import tz.co.pharmacy.modules.inventory.ports.inbound.ProductSnapshots;
import tz.co.pharmacy.platform.bus.EventScopes;

/** Keeps inventory's product snapshots current from the catalogue's product.changed. */
@Component
@RequiredArgsConstructor
class InventoryReactions {

  private final ProductSnapshots productSnapshots;

  @Async
  @TransactionalEventListener
  void on(ProductChanged event) {
    EventScopes.run(
        event.metadata(),
        "inventory-snapshots",
        () ->
            productSnapshots.upsert(
                new ProductSnapshot(
                    event.productUid(),
                    event.name(),
                    event.genericName(),
                    event.sku(),
                    event.dosageForm(),
                    event.strength(),
                    event.categoryUid(),
                    event.categoryName(),
                    event.reorderLevel(),
                    event.sellingPrice(),
                    event.active(),
                    event.productType(),
                    event.trackingMode(),
                    event.unitOfMeasure())));
  }
}
