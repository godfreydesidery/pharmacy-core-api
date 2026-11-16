package tz.co.pharmacy.modules.purchasing.ports.outbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** What reorder suggestions read from catalogue, inventory, sales and partners. */
public interface ReorderInputs {

  /** The tenant's active products. */
  List<ReorderProduct> products();

  /** Sellable quantity on hand at the current branch; absent means none. */
  Map<UUID, Integer> onHand(Collection<UUID> productUids);

  /** Units sold at the branch between the instants, net of returns; absent means none. */
  Map<UUID, Integer> unitsSold(UUID branchUid, Instant from, Instant to);

  /** The supplier's days from order to delivery; 7 when the supplier is unknown. */
  int leadTimeDays(UUID supplierUid);

  /**
   * A product to plan for. {@code orderMultiple} is the quantity orders are rounded up to: the
   * units per pack when the product is broken from a pack (orders come in whole packs), else 1.
   */
  record ReorderProduct(
      UUID uid, String name, int reorderLevel, BigDecimal costPrice, int orderMultiple) {}
}
