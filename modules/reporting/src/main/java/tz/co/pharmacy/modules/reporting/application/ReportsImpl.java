package tz.co.pharmacy.modules.reporting.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.reporting.ports.inbound.DashboardView;
import tz.co.pharmacy.modules.reporting.ports.inbound.ExpenseLine;
import tz.co.pharmacy.modules.reporting.ports.inbound.Metric;
import tz.co.pharmacy.modules.reporting.ports.inbound.PaymentMix;
import tz.co.pharmacy.modules.reporting.ports.inbound.ProductReportRow;
import tz.co.pharmacy.modules.reporting.ports.inbound.ProfitLoss;
import tz.co.pharmacy.modules.reporting.ports.inbound.Reports;
import tz.co.pharmacy.modules.reporting.ports.inbound.SalesReport;
import tz.co.pharmacy.modules.reporting.ports.inbound.SalesReportRow;
import tz.co.pharmacy.modules.reporting.ports.inbound.TopProduct;
import tz.co.pharmacy.modules.reporting.ports.inbound.TrendPoint;
import tz.co.pharmacy.modules.reporting.ports.outbound.DashboardFacts;
import tz.co.pharmacy.modules.reporting.ports.outbound.ReportingTenant;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.kernel.ExecutionContext;

/**
 * Reads the read models, never the modules' own tables, so a report costs the same however many
 * sales a day holds. Sales figures are totals including tax ({@code net + tax}); gross profit is
 * {@code net - cost}.
 */
@Service
class ReportsImpl implements Reports {

  private static final int DASHBOARD_LIST_LIMIT = 5;

  private final JdbcTemplate jdbc;
  private final ReportingTenant tenant;
  private final DashboardFacts facts;

  ReportsImpl(DataSource dataSource, ReportingTenant tenant, DashboardFacts facts) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.tenant = tenant;
    this.facts = facts;
  }

  private record Totals(
      long invoices,
      BigDecimal gross,
      BigDecimal discount,
      BigDecimal tax,
      BigDecimal net,
      BigDecimal cost) {

    BigDecimal sales() {
      return Amounts.money(net.add(tax));
    }

    BigDecimal profit() {
      return Amounts.money(net.subtract(cost));
    }
  }

  @Override
  @Transactional(readOnly = true)
  public DashboardView dashboard(int days) {
    Set<UUID> branches = ExecutionContext.reportBranches();
    LocalDate to = LocalDate.now(tenant.zone());
    LocalDate from = to.minusDays(days - 1L);
    LocalDate previousTo = from.minusDays(1);
    LocalDate previousFrom = previousTo.minusDays(days - 1L);

    Totals current = totals(branches, from, to);
    Totals previous = totals(branches, previousFrom, previousTo);
    BigDecimal purchases = purchases(branches, from, to);
    BigDecimal previousPurchases = purchases(branches, previousFrom, previousTo);
    int expiryAlertDays = tenant.expiryAlertDays();

    return new DashboardView(
        tenant.currency(),
        days,
        metric(current.sales(), previous.sales()),
        metric(purchases, previousPurchases),
        metric(current.profit(), previous.profit()),
        metric(BigDecimal.valueOf(current.invoices()), BigDecimal.valueOf(previous.invoices())),
        facts.customers(),
        facts.stock(branches, expiryAlertDays),
        trend(branches, from, to),
        topProducts(branches, from, to, DASHBOARD_LIST_LIMIT),
        paymentMix(branches, from, to),
        facts.expiringBatches(branches, expiryAlertDays, DASHBOARD_LIST_LIMIT),
        facts.recentSales(branches, DASHBOARD_LIST_LIMIT),
        facts.credit());
  }

  @Override
  @Transactional(readOnly = true)
  public SalesReport sales(LocalDate from, LocalDate to) {
    Set<UUID> branches = ExecutionContext.reportBranches();
    List<Object> arguments = new ArrayList<>(List.of(from, to));
    String branchClause = BranchFilter.clause(branches, arguments);
    Map<LocalDate, SalesReportRow> byDay = new HashMap<>();
    jdbc.query(
        "select day, sum(invoices) as invoices, sum(gross) as gross, sum(discount) as discount,"
            + " sum(tax) as tax, sum(net) as net, sum(cost) as cost from reporting.daily_sales"
            + " where day between ? and ?"
            + branchClause
            + " group by day",
        row -> {
          LocalDate day = row.getObject("day", LocalDate.class);
          byDay.put(
              day,
              row(
                  day,
                  new Totals(
                      row.getLong("invoices"),
                      row.getBigDecimal("gross"),
                      row.getBigDecimal("discount"),
                      row.getBigDecimal("tax"),
                      row.getBigDecimal("net"),
                      row.getBigDecimal("cost"))));
        },
        arguments.toArray());
    List<SalesReportRow> rows = new ArrayList<>();
    for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
      rows.add(byDay.getOrDefault(day, row(day, empty())));
    }
    return new SalesReport(rows, row(null, totals(branches, from, to)));
  }

  @Override
  @Transactional(readOnly = true)
  public List<ProductReportRow> products(LocalDate from, LocalDate to, int limit) {
    List<Object> arguments = new ArrayList<>(List.of(from, to));
    String branchClause = BranchFilter.clause(ExecutionContext.reportBranches(), arguments);
    arguments.add(limit);
    return jdbc.query(
        "select product_uid, max(product_name) as product_name, sum(quantity) as quantity,"
            + " sum(net) as net, sum(cost) as cost from reporting.daily_product_sales"
            + " where day between ? and ?"
            + branchClause
            + " group by product_uid order by sum(net) desc, max(product_name) limit ?",
        (row, index) -> {
          BigDecimal net = Amounts.money(row.getBigDecimal("net"));
          BigDecimal cost = Amounts.money(row.getBigDecimal("cost"));
          return new ProductReportRow(
              row.getObject("product_uid", UUID.class),
              row.getString("product_name"),
              row.getLong("quantity"),
              net,
              cost,
              Amounts.money(net.subtract(cost)));
        },
        arguments.toArray());
  }

  @Override
  @Transactional(readOnly = true)
  public ProfitLoss profitLoss(LocalDate from, LocalDate to) {
    Set<UUID> branches = ExecutionContext.reportBranches();
    Totals totals = totals(branches, from, to);
    List<Object> arguments = new ArrayList<>(List.of(from, to));
    String branchClause = BranchFilter.clause(branches, arguments);
    List<ExpenseLine> expenses =
        jdbc.query(
            "select category, sum(amount) as amount from reporting.daily_expenses"
                + " where day between ? and ?"
                + branchClause
                + " group by category order by sum(amount) desc",
            (row, index) ->
                new ExpenseLine(
                    row.getString("category"), Amounts.money(row.getBigDecimal("amount"))),
            arguments.toArray());
    BigDecimal totalExpenses =
        Amounts.money(
            expenses.stream().map(ExpenseLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add));
    BigDecimal grossProfit = totals.profit();
    return new ProfitLoss(
        Amounts.money(totals.net()),
        Amounts.money(totals.cost()),
        grossProfit,
        expenses,
        totalExpenses,
        Amounts.money(grossProfit.subtract(totalExpenses)));
  }

  @Override
  @Transactional(readOnly = true)
  public List<PaymentMix> payments(LocalDate from, LocalDate to) {
    return paymentMix(ExecutionContext.reportBranches(), from, to);
  }

  private Totals totals(Set<UUID> branches, LocalDate from, LocalDate to) {
    List<Object> arguments = new ArrayList<>(List.of(from, to));
    String branchClause = BranchFilter.clause(branches, arguments);
    return jdbc.queryForObject(
        "select coalesce(sum(invoices), 0) as invoices, coalesce(sum(gross), 0) as gross,"
            + " coalesce(sum(discount), 0) as discount, coalesce(sum(tax), 0) as tax,"
            + " coalesce(sum(net), 0) as net, coalesce(sum(cost), 0) as cost"
            + " from reporting.daily_sales where day between ? and ?"
            + branchClause,
        (row, index) ->
            new Totals(
                row.getLong("invoices"),
                Amounts.money(row.getBigDecimal("gross")),
                Amounts.money(row.getBigDecimal("discount")),
                Amounts.money(row.getBigDecimal("tax")),
                Amounts.money(row.getBigDecimal("net")),
                Amounts.money(row.getBigDecimal("cost"))),
        arguments.toArray());
  }

  private BigDecimal purchases(Set<UUID> branches, LocalDate from, LocalDate to) {
    List<Object> arguments = new ArrayList<>(List.of(from, to));
    String branchClause = BranchFilter.clause(branches, arguments);
    return Amounts.money(
        jdbc.queryForObject(
            "select coalesce(sum(total), 0) from reporting.daily_purchases"
                + " where day between ? and ?"
                + branchClause,
            BigDecimal.class,
            arguments.toArray()));
  }

  private List<TrendPoint> trend(Set<UUID> branches, LocalDate from, LocalDate to) {
    List<Object> arguments = new ArrayList<>(List.of(from, to));
    String branchClause = BranchFilter.clause(branches, arguments);
    Map<LocalDate, BigDecimal> byDay = new HashMap<>();
    jdbc.query(
        "select day, sum(net + tax) as total from reporting.daily_sales"
            + " where day between ? and ?"
            + branchClause
            + " group by day",
        row -> {
          byDay.put(row.getObject("day", LocalDate.class), row.getBigDecimal("total"));
        },
        arguments.toArray());
    List<TrendPoint> points = new ArrayList<>();
    for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
      points.add(new TrendPoint(day, Amounts.money(byDay.get(day))));
    }
    return points;
  }

  private List<TopProduct> topProducts(
      Set<UUID> branches, LocalDate from, LocalDate to, int limit) {
    List<Object> arguments = new ArrayList<>(List.of(from, to));
    String branchClause = BranchFilter.clause(branches, arguments);
    arguments.add(limit);
    return jdbc.query(
        "select product_uid, max(product_name) as product_name, sum(quantity) as quantity,"
            + " sum(net) as net from reporting.daily_product_sales where day between ? and ?"
            + branchClause
            + " group by product_uid order by sum(net) desc, max(product_name) limit ?",
        (row, index) ->
            new TopProduct(
                row.getObject("product_uid", UUID.class),
                row.getString("product_name"),
                row.getLong("quantity"),
                Amounts.money(row.getBigDecimal("net"))),
        arguments.toArray());
  }

  private List<PaymentMix> paymentMix(Set<UUID> branches, LocalDate from, LocalDate to) {
    List<Object> arguments = new ArrayList<>(List.of(from, to));
    String branchClause = BranchFilter.clause(branches, arguments);
    return jdbc.query(
        "select method, sum(amount) as amount, sum(payments) as payments"
            + " from reporting.daily_payments where day between ? and ?"
            + branchClause
            + " group by method order by sum(amount) desc",
        (row, index) ->
            new PaymentMix(
                row.getString("method"),
                Amounts.money(row.getBigDecimal("amount")),
                row.getLong("payments")),
        arguments.toArray());
  }

  private static Metric metric(BigDecimal value, BigDecimal previous) {
    return new Metric(value, previous, Amounts.changePercent(value, previous));
  }

  private static Totals empty() {
    return new Totals(0, Amounts.ZERO, Amounts.ZERO, Amounts.ZERO, Amounts.ZERO, Amounts.ZERO);
  }

  private static SalesReportRow row(LocalDate day, Totals totals) {
    return new SalesReportRow(
        day,
        totals.invoices(),
        Amounts.money(totals.gross()),
        Amounts.money(totals.discount()),
        Amounts.money(totals.tax()),
        Amounts.money(totals.net()),
        Amounts.money(totals.cost()),
        totals.profit());
  }
}
