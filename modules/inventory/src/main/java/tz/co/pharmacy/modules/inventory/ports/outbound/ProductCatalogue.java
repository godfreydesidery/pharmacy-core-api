package tz.co.pharmacy.modules.inventory.ports.outbound;

import java.util.Optional;
import java.util.UUID;
import tz.co.pharmacy.modules.inventory.ports.inbound.ProductSnapshot;

/** The catalogue's facts about a product, read when inventory has no snapshot of it yet. */
public interface ProductCatalogue {

  Optional<ProductSnapshot> product(UUID productUid);
}
