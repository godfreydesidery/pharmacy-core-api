package tz.co.pharmacy.modules.purchasing.ports.inbound;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * What the current branch should order, from how fast each product sells. For each product:
 * velocity = units sold in the window ÷ window days; target = max(velocity × (supplier lead time +
 * cover days), reorder level); suggested = target − on hand − on order, rounded up to the order
 * multiple; only products with a positive suggestion are listed, grouped by the supplier that last
 * delivered them (null for none yet).
 */
public interface ReorderSuggestions {

  /** {@code windowDays} and {@code coverDays} default to 30, each 1..365 (422 invalid_days). */
  Plan suggest(Integer windowDays, Integer coverDays);

  /**
   * One purchase order per supplier, in the base currency at the given quantities and costs; lines
   * with quantity 0 are skipped. 422 supplier_required for a group without a supplier.
   */
  List<PurchaseOrderView> order(List<SupplierOrder> orders);

  record Plan(int windowDays, int coverDays, List<SupplierGroup> groups) {}

  record SupplierGroup(
      UUID supplierUid,
      String supplierName,
      int leadTimeDays,
      BigDecimal total,
      List<Suggestion> lines) {}

  record Suggestion(
      UUID productUid,
      String productName,
      int unitsSold,
      BigDecimal dailyVelocity,
      int onHand,
      int onOrder,
      int reorderLevel,
      int orderMultiple,
      int suggested,
      BigDecimal unitCost,
      BigDecimal lineTotal) {}

  record SupplierOrder(UUID supplierUid, List<OrderLine> lines) {}

  record OrderLine(UUID productUid, Integer quantity, BigDecimal unitCost) {}
}
