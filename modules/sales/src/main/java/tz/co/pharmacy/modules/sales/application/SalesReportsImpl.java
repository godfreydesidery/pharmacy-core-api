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

  /**
   * Who a till row belongs to: the account, else the email for rows with no account; with the
   * account uid and the email it last used in the rows.
   */
  private static final String WHO =
      "coalesce(s.created_by_uid::text, s.created_by) as cashier, s.created_by_uid as cashier_uid,"
          + " (array_agg(s.created_by order by s.created_at desc))[1] as reference";

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
        "select "
            + WHO
            + ", max(s.cashier_name) as name, count(*) as sales,"
            + " coalesce(sum(s.total), 0) as total, coalesce(sum(s.change_due), 0) as change"
            + " from sales.sales s where s.created_at >= ? and s.created_at < ?"
            + branches
            + " group by 1, 2",
        r -> {
          Row row = row(rows, r);
          row.name = r.getString("name");
          row.sales = r.getLong("sales");
          row.total = r.getBigDecimal("total");
          row.change = r.getBigDecimal("change");
        },
        args.toArray());
    jdbc.query(
        "select "
            + WHO
            + ", p.method,"
            + " coalesce(sum(coalesce(p.base_amount, p.amount)), 0) as amount"
            + " from sales.sale_payments p join sales.sales s on s.id = p.sale_id"
            + " where s.created_at >= ? and s.created_at < ?"
            + branches
            + " group by 1, 2, 4",
        r -> {
          Row row = row(rows, r);
          row.tenders.merge(r.getString("method"), r.getBigDecimal("amount"), BigDecimal::add);
        },
        args.toArray());
    jdbc.query(
        "select "
            + WHO
            + ", count(*) as returns,"
            + " coalesce(sum(s.refund_total + coalesce(s.fx_difference, 0)), 0) as refunded,"
            + " coalesce(sum("
            + ReturnSql.cashPaidOut("s")
            + "), 0) as cash"
            + " from sales.sale_returns s where s.created_at >= ? and s.created_at < ?"
            + branches
            + " group by 1, 2",
        r -> {
          Row row = row(rows, r);
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
                  r.name == null || r.name.isBlank() ? r.reference : r.name,
                  r.reference,
                  r.uid,
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

  /** A line's VAT category: as kept, else the one its rate implies (older lines). */
  private static final String CATEGORY =
      "coalesce(l.tax_category, case when l.tax_rate_percent > 0 then 'STANDARD'"
          + " else 'EXEMPT' end)";

  @Override
  public List<VatCategoryLine> vatByCategory(LocalDate from, LocalDate to, java.time.ZoneId zone) {
    LocalDate end = to == null ? LocalDate.now(zone) : to;
    LocalDate start = from == null ? end.withDayOfMonth(1) : from;
    if (end.isBefore(start)) {
      throw Refusal.invalid("invalid_period", "to is before from.", "to");
    }
    Object[] args = {
      start.atStartOfDay(zone).toOffsetDateTime(),
      end.plusDays(1).atStartOfDay(zone).toOffsetDateTime()
    };
    Map<String, BigDecimal[]> figures = new LinkedHashMap<>();
    for (tz.co.pharmacy.platform.kernel.TaxCategory category :
        tz.co.pharmacy.platform.kernel.TaxCategory.values()) {
      figures.put(
          category.name(),
          new BigDecimal[] {
            Amounts.ZERO, Amounts.ZERO, Amounts.ZERO, Amounts.ZERO, Amounts.ZERO, Amounts.ZERO
          });
    }
    jdbc.query(
        "select "
            + CATEGORY
            + " as category, coalesce(sum(l.line_total - l.tax), 0) as net,"
            + " coalesce(sum(l.tax), 0) as tax"
            + " from sales.sale_lines l join sales.sales s on s.id = l.sale_id"
            + " where s.created_at >= ? and s.created_at < ? group by 1",
        r -> {
          BigDecimal[] row = figures.get(r.getString("category"));
          if (row != null) {
            row[0] = row[0].add(r.getBigDecimal("net"));
            row[1] = row[1].add(r.getBigDecimal("tax"));
          }
        },
        args);
    jdbc.query(
        "select "
            + CATEGORY
            + " as category, coalesce(sum(rl.refund - rl.tax), 0) as net,"
            + " coalesce(sum(rl.tax), 0) as tax"
            + " from sales.sale_return_lines rl join sales.sale_returns r on r.id = rl.return_id"
            + " join sales.sale_lines l on l.uid = rl.sale_line_uid"
            + " where r.created_at >= ? and r.created_at < ? group by 1",
        r -> {
          BigDecimal[] row = figures.get(r.getString("category"));
          if (row != null) {
            row[2] = row[2].add(r.getBigDecimal("net"));
            row[3] = row[3].add(r.getBigDecimal("tax"));
          }
        },
        args);
    // Sales debit notes (STD-2): more charged on a sale, under the category they were issued in.
    jdbc.query(
        "select d.tax_category as category, coalesce(sum(d.net), 0) as net,"
            + " coalesce(sum(d.tax), 0) as tax from sales.debit_notes d"
            + " where d.created_at >= ? and d.created_at < ? group by 1",
        r -> {
          BigDecimal[] row = figures.get(r.getString("category"));
          if (row != null) {
            row[4] = row[4].add(r.getBigDecimal("net"));
            row[5] = row[5].add(r.getBigDecimal("tax"));
          }
        },
        args);
    List<VatCategoryLine> lines = new ArrayList<>();
    figures.forEach(
        (category, row) ->
            lines.add(
                new VatCategoryLine(
                    tz.co.pharmacy.platform.kernel.TaxCategory.valueOf(category),
                    Amounts.money(row[0]),
                    Amounts.money(row[1]),
                    Amounts.money(row[2]),
                    Amounts.money(row[3]),
                    Amounts.money(row[0].subtract(row[2]).add(row[4])),
                    Amounts.money(row[1].subtract(row[3]).add(row[5])),
                    Amounts.money(row[4]),
                    Amounts.money(row[5]))));
    return lines;
  }

  /** The person's row, keyed by account (or the email, for rows with no account). */
  private static Row row(Map<String, Row> rows, java.sql.ResultSet r) throws java.sql.SQLException {
    Row row = rows.computeIfAbsent(r.getString("cashier"), k -> new Row());
    if (row.uid == null) {
      row.uid = r.getObject("cashier_uid", UUID.class);
    }
    if (row.reference == null) {
      row.reference = r.getString("reference");
    }
    return row;
  }

  private static final class Row {
    UUID uid;
    String reference;
    String name;
    long sales;
    BigDecimal total = Amounts.ZERO;
    BigDecimal change = Amounts.ZERO;
    final Map<String, BigDecimal> tenders = new TreeMap<>();
    long returns;
    BigDecimal refunded = Amounts.ZERO;
    BigDecimal cashRefunded = Amounts.ZERO;
  }
}
