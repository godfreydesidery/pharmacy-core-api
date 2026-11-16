package tz.co.pharmacy.modules.purchasing.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.purchasing.ports.inbound.CreatePurchaseOrderCommand;
import tz.co.pharmacy.modules.purchasing.ports.inbound.OrderLineCommand;
import tz.co.pharmacy.modules.purchasing.ports.inbound.PurchaseOrderService;
import tz.co.pharmacy.modules.purchasing.ports.inbound.PurchaseOrderView;
import tz.co.pharmacy.modules.purchasing.ports.inbound.ReorderSuggestions;
import tz.co.pharmacy.modules.purchasing.ports.outbound.ReorderInputs;
import tz.co.pharmacy.modules.purchasing.ports.outbound.ReorderInputs.ReorderProduct;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Refusal;

@Service
class ReorderSuggestionsImpl implements ReorderSuggestions {

  private static final int DEFAULT_DAYS = 30;

  private final ReorderInputs inputs;
  private final PurchaseOrderService purchaseOrders;
  private final JdbcTemplate jdbc;
  private final Clock clock;

  ReorderSuggestionsImpl(
      ReorderInputs inputs,
      PurchaseOrderService purchaseOrders,
      DataSource dataSource,
      Clock clock) {
    this.inputs = inputs;
    this.purchaseOrders = purchaseOrders;
    this.jdbc = new JdbcTemplate(dataSource);
    this.clock = clock;
  }

  @Override
  @Transactional(readOnly = true)
  public Plan suggest(Integer windowDays, Integer coverDays) {
    int window = days(windowDays, "windowDays");
    int cover = days(coverDays, "coverDays");
    UUID branch = ExecutionContext.requireBranch();
    List<ReorderProduct> products = inputs.products();
    List<UUID> uids = products.stream().map(ReorderProduct::uid).toList();
    Instant now = clock.instant();
    Map<UUID, Integer> sold = inputs.unitsSold(branch, now.minus(Duration.ofDays(window)), now);
    Map<UUID, Integer> onHand = inputs.onHand(uids);
    Map<UUID, Integer> onOrder = onOrder(branch);
    Map<UUID, LastReceipt> last = lastReceipts();
    Map<UUID, Integer> leadTimes = new HashMap<>();

    Map<UUID, List<Suggestion>> bySupplier = new LinkedHashMap<>();
    Map<UUID, String> supplierNames = new HashMap<>();
    for (ReorderProduct product : products) {
      int units = sold.getOrDefault(product.uid(), 0);
      if (units == 0 && product.reorderLevel() <= 0) {
        continue;
      }
      LastReceipt receipt = last.get(product.uid());
      UUID supplier = receipt == null ? null : receipt.supplierUid();
      int leadTime =
          supplier == null ? 7 : leadTimes.computeIfAbsent(supplier, inputs::leadTimeDays);
      BigDecimal velocity =
          BigDecimal.valueOf(units).divide(BigDecimal.valueOf(window), 4, RoundingMode.HALF_UP);
      int target =
          Math.max(
              velocity
                  .multiply(BigDecimal.valueOf((long) leadTime + cover))
                  .setScale(0, RoundingMode.CEILING)
                  .intValue(),
              product.reorderLevel());
      int held = onHand.getOrDefault(product.uid(), 0);
      int coming = onOrder.getOrDefault(product.uid(), 0);
      int need = target - held - coming;
      if (need <= 0) {
        continue;
      }
      int multiple = Math.max(1, product.orderMultiple());
      int suggested = ((need + multiple - 1) / multiple) * multiple;
      BigDecimal cost =
          Amounts.money(
              receipt != null && receipt.baseUnitCost() != null
                  ? receipt.baseUnitCost()
                  : product.costPrice() == null ? BigDecimal.ZERO : product.costPrice());
      if (supplier != null) {
        supplierNames.put(supplier, receipt.supplierName());
      }
      bySupplier
          .computeIfAbsent(supplier, k -> new ArrayList<>())
          .add(
              new Suggestion(
                  product.uid(),
                  product.name(),
                  units,
                  velocity.setScale(2, RoundingMode.HALF_UP),
                  held,
                  coming,
                  product.reorderLevel(),
                  multiple,
                  suggested,
                  cost,
                  Amounts.money(cost.multiply(BigDecimal.valueOf(suggested)))));
    }
    List<SupplierGroup> groups = new ArrayList<>();
    bySupplier.forEach(
        (supplier, lines) -> {
          lines.sort(Comparator.comparing(Suggestion::productName, String.CASE_INSENSITIVE_ORDER));
          groups.add(
              new SupplierGroup(
                  supplier,
                  supplier == null ? null : supplierNames.get(supplier),
                  supplier == null ? 7 : leadTimes.getOrDefault(supplier, 7),
                  Amounts.money(
                      lines.stream()
                          .map(Suggestion::lineTotal)
                          .reduce(BigDecimal.ZERO, BigDecimal::add)),
                  lines));
        });
    // Known suppliers by name, then the products no supplier has delivered yet.
    groups.sort(
        Comparator.comparing(
            (SupplierGroup g) -> g.supplierName() == null ? "￿" : g.supplierName(),
            String.CASE_INSENSITIVE_ORDER));
    return new Plan(window, cover, groups);
  }

  @Override
  @Transactional
  public List<PurchaseOrderView> order(List<SupplierOrder> orders) {
    if (orders == null || orders.isEmpty()) {
      throw Refusal.invalid("orders_required", "Choose at least one supplier's order.", "orders");
    }
    List<PurchaseOrderView> created = new ArrayList<>();
    for (int i = 0; i < orders.size(); i++) {
      SupplierOrder order = orders.get(i);
      if (order.supplierUid() == null) {
        throw Refusal.invalid(
            "supplier_required", "Choose a supplier for these products.", "orders[" + i + "]");
      }
      List<OrderLineCommand> lines =
          order.lines() == null
              ? List.of()
              : order.lines().stream()
                  .filter(line -> line.quantity() != null && line.quantity() > 0)
                  .map(
                      line ->
                          new OrderLineCommand(
                              line.productUid(),
                              line.quantity(),
                              line.unitCost() == null ? BigDecimal.ZERO : line.unitCost()))
                  .toList();
      if (lines.isEmpty()) {
        continue;
      }
      created.add(
          purchaseOrders.create(
              new CreatePurchaseOrderCommand(
                  order.supplierUid(), null, "From reorder suggestions", lines, null, null)));
    }
    if (created.isEmpty()) {
      throw Refusal.invalid("orders_required", "Every quantity is zero.", "orders");
    }
    return created;
  }

  private static int days(Integer value, String field) {
    if (value == null) {
      return DEFAULT_DAYS;
    }
    if (value < 1 || value > 365) {
      throw Refusal.invalid("invalid_days", "Choose 1 to 365 days.", field);
    }
    return value;
  }

  /** Units ordered at the branch and not yet received, on open orders. */
  private Map<UUID, Integer> onOrder(UUID branch) {
    Map<UUID, Integer> out = new HashMap<>();
    jdbc.query(
        "select l.product_uid, sum(l.quantity - l.received_quantity) as units"
            + " from purchasing.purchase_order_lines l"
            + " join purchasing.purchase_orders o on o.id = l.purchase_order_id"
            + " where o.branch_uid = ? and o.status in ('ORDERED', 'PARTIALLY_RECEIVED')"
            + " group by l.product_uid",
        r -> {
          int units = r.getInt("units");
          if (units > 0) {
            out.put(r.getObject("product_uid", UUID.class), units);
          }
        },
        branch);
    return out;
  }

  /** The latest goods receipt of each product, at any branch: its supplier and base unit cost. */
  private Map<UUID, LastReceipt> lastReceipts() {
    Map<UUID, LastReceipt> out = new HashMap<>();
    jdbc.query(
        "select distinct on (l.product_uid) l.product_uid, r.supplier_uid, r.supplier_name,"
            + " l.unit_cost * coalesce(r.exchange_rate, 1) as base_cost"
            + " from purchasing.goods_receipt_lines l"
            + " join purchasing.goods_receipts r on r.id = l.goods_receipt_id"
            + " order by l.product_uid, r.id desc",
        r -> {
          out.put(
              r.getObject("product_uid", UUID.class),
              new LastReceipt(
                  r.getObject("supplier_uid", UUID.class),
                  r.getString("supplier_name"),
                  r.getBigDecimal("base_cost")));
        });
    return out;
  }

  private record LastReceipt(UUID supplierUid, String supplierName, BigDecimal baseUnitCost) {}
}
