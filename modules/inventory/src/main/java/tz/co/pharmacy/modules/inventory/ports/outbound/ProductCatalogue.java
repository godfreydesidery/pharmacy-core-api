package tz.co.pharmacy.modules.inventory.ports.outbound;

import java.util.Optional;
import java.util.UUID;
import tz.co.pharmacy.modules.inventory.ports.inbound.ProductSnapshot;

/** The catalogue's facts about a product, read when inventory has no snapshot of it yet. */
public interface ProductCatalogue {

  Optional<ProductSnapshot> product(UUID productUid);

  /**
   * The product's dispensing class now: {@code OTC}, {@code PRESCRIPTION} or {@code CONTROLLED}
   * (null for an unknown product). Read live, never from the snapshot, so a write-off or repack
   * follows the class the catalogue holds at that moment.
   */
  String schedule(UUID productUid);
}
