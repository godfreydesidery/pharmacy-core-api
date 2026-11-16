package tz.co.pharmacy.modules.sales.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.sales.ports.inbound.SalesReports;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * Cashier figures as plain SQL over the module's tables; row-level security keeps them to the
 * current tenant, and the caller's report branches narrow them further.
 */
@Service
@Transactional(readOnly = true)
class SalesReportsImpl implements SalesReports {

  private final JdbcTemplate jdbc;

  SalesReportsImpl(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  public List<CashierSummary> cashiers(LocalDate from, LocalDate to, java.time.ZoneId zone) {
    LocalDate start = from == null ? LocalDate.now(zone) : from;
    LocalDate end = to == null ? start : to;
    if (end.isBefore(start)) {
      throw Refusal.invalid("invalid_period", "to is before from.", "to");
    }
    List<Object> args = new ArrayList<>();
    args.add(start.atStartOfDay(zone).toOffsetDateTime());
    args.add(end.plusDays(1).atStartOfDay(zone).toOffsetDateTime());
    String branches = branchClause("s.branch_uid", args);
    Map<String, Row> rows = new TreeMap<>();
    jdbc.query(
        "select coalesce(s.cashier_name, s.created_by) as cashier, count(*) as sales,"
            + " coalesce(sum(s.total), 0) as total, coalesce(sum(s.change_due), 0) as change"
            + " from sales.sales s where s.created_at >= ? and s.created_at < ?"
            + branches
            + " group by 1",
        r -> {
          Row row = rows.computeIfAbsent(r.getString("cashier"), k -> new Row());
          row.sales = r.getLong("sales");
          row.total = r.getBigDecimal("total");
          row.change = r.getBigDecimal("change");
        },
        args.toArray());
    jdbc.query(
        "select coalesce(s.cashier_name, s.created_by) as cashier, p.method,"
            + " coalesce(sum(coalesce(p.base_amount, p.amount)), 0) as amount"
            + " from sales.sale_payments p join sales.sales s on s.id = p.sale_id"
            + " where s.created_at >= ? and s.created_at < ?"
            + branches
            + " group by 1, 2",
        r -> {
          Row row = rows.computeIfAbsent(r.getString("cashier"), k -> new Row());
          row.tenders.merge(r.getString("method"), r.getBigDecimal("amount"), BigDecimal::add);
        },
        args.toArray());
    jdbc.query(
        "select s.created_by as cashier, count(*) as returns,"
            + " coalesce(sum(s.refund_total + coalesce(s.fx_difference, 0)), 0) as refunded,"
            + " coalesce(sum(case when s.refund_method = 'CASH'"
            + " then s.refund_total + coalesce(s.fx_difference, 0) else 0 end), 0) as cash"
            + " from sales.sale_returns s where s.created_at >= ? and s.created_at < ?"
            + branches
            + " group by 1",
        r -> {
          Row row = rows.computeIfAbsent(r.getString("cashier"), k -> new Row());
          row.returns = r.getLong("returns");
          row.refunded = r.getBigDecimal("refunded");
          row.cashRefunded = r.getBigDecimal("cash");
        },
        args.toArray());
    List<CashierSummary> out = new ArrayList<>();
    rows.forEach(
        (cashier, r) -> {
          Map<String, BigDecimal> tenders = new LinkedHashMap<>();
          r.tenders.forEach((m, v) -> tenders.put(m, Amounts.money(v)));
          // Cash is reported as kept: what was tendered less the change handed back.
          BigDecimal tendered = tenders.getOrDefault("CASH", Amounts.ZERO);
          if (tenders.containsKey("CASH") || r.change.signum() != 0) {
            tenders.put("CASH", Amounts.money(tendered.subtract(r.change)));
          }
          BigDecimal cash = tendered.subtract(r.change).subtract(r.cashRefunded);
          out.add(
              new CashierSummary(
                  cashier,
                  r.sales,
                  Amounts.money(r.total),
                  tenders,
                  r.returns,
                  Amounts.money(r.refunded),
                  Amounts.money(cash),
                  Amounts.money(tendered)));
        });
    return out;
  }

  @Override
  public Map<UUID, Integer> unitsSold(
      UUID branchUid, java.time.Instant from, java.time.Instant to) {
    Map<UUID, Integer> sold = new java.util.HashMap<>();
    jdbc.query(
        "select l.product_uid, sum(l.quantity - l.returned_quantity) as units"
            + " from sales.sale_lines l join sales.sales s on s.id = l.sale_id"
            + " where s.branch_uid = ? and s.created_at >= ? and s.created_at < ?"
            + " group by l.product_uid",
        r -> {
          int units = r.getInt("units");
          if (units > 0) {
            sold.put(r.getObject("product_uid", UUID.class), units);
          }
        },
        branchUid,
        from.atOffset(ZoneOffset.UTC),
        to.atOffset(ZoneOffset.UTC));
    return sold;
  }

  @Override
  public List<BatchSale> batchSales(UUID batchUid) {
    if (batchUid == null) {
      throw Refusal.invalid("invalid_value", "batchUid is required.", "batchUid");
    }
    List<Object> args = new ArrayList<>();
    args.add(batchUid);
    String branches = branchClause("s.branch_uid", args);
    return jdbc.query(
        "select s.uid, s.number, s.created_at, s.branch_name, s.customer_uid, s.customer_name,"
            + " s.patient_name, sum(l.quantity) as quantity,"
            + " sum(l.returned_quantity) as returned"
            + " from sales.sale_lines l join sales.sales s on s.id = l.sale_id"
            + " where l.batch_uid = ?"
            + branches
            + " group by s.id, s.uid, s.number, s.created_at, s.branch_name, s.customer_uid,"
            + " s.customer_name, s.patient_name order by s.created_at desc limit 500",
        (r, i) ->
            new BatchSale(
                r.getObject("uid", UUID.class),
                r.getString("number"),
                r.getTimestamp("created_at").toInstant(),
                r.getString("branch_name"),
                r.getObject("customer_uid", UUID.class),
                r.getString("customer_name"),
                r.getString("patient_name"),
                r.getInt("quantity"),
                r.getInt("returned")),
        args.toArray());
  }

  private static String branchClause(String column, List<Object> args) {
    Set<UUID> branches = ExecutionContext.reportBranches();
    if (branches == null) {
      return "";
    }
    if (branches.isEmpty()) {
      return " and false";
    }
    args.addAll(branches);
    return " and "
        + column
        + " in ("
        + String.join(", ", Collections.nCopies(branches.size(), "?"))
        + ")";
  }

  private static final class Row {
    long sales;
    BigDecimal total = Amounts.ZERO;
    BigDecimal change = Amounts.ZERO;
    final Map<String, BigDecimal> tenders = new TreeMap<>();
    long returns;
    BigDecimal refunded = Amounts.ZERO;
    BigDecimal cashRefunded = Amounts.ZERO;
  }
}
