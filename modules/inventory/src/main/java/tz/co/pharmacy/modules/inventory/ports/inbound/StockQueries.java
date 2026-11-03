package tz.co.pharmacy.modules.inventory.ports.inbound;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;

/** Reads of stock. {@code branchUids} null means every branch of the tenant. */
public interface StockQueries {

  /** The current branch's stock by product name (offset paging). */
  Page<StockItemView> stock(StockFilter filter, PageQuery query);

  StockSummaryView summary(Set<UUID> branchUids, int expiryAlertDays);

  /** The current branch's batches of a product in stock, earliest expiry first. */
  List<BatchView> batches(UUID productUid);

  /** Batches in stock expiring within {@code withinDays}, expired ones included, earliest first. */
  Page<ExpiringBatchView> expiring(Set<UUID> branchUids, int withinDays, PageQuery query);

  /** The current branch's stock ledger, newest first. */
  Page<MovementView> movements(UUID productUid, MovementType type, PageQuery query);

  /** Sellable (unexpired) quantity on hand at the current branch, 0 for a product with none. */
  Map<UUID, Integer> onHand(Collection<UUID> productUids);

  /** Units of the product the tenant holds: at any branch (expired too) or in transit. */
  long unitsHeld(UUID productUid);

  /** The current branch's units of a SERIAL product, by serial number; status null for all. */
  List<SerialUnitView> serials(UUID productUid, SerialStatus status);

  /** Transfers of the current branch: OUTGOING, INCOMING or, with null, both; newest first. */
  Page<TransferView> transfers(String direction, PageQuery query);
}
