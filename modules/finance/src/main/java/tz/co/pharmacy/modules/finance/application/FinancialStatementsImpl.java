package tz.co.pharmacy.modules.finance.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.finance.ports.inbound.AccountType;
import tz.co.pharmacy.modules.finance.ports.inbound.FinancialStatements;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * Statements as plain SQL over the module's ledger; row-level security keeps them to the current
 * tenant without a tenant predicate.
 */
@Service
class FinancialStatementsImpl implements FinancialStatements {

  private final JdbcTemplate jdbc;
  private final ChartOfAccounts chart;

  FinancialStatementsImpl(DataSource dataSource, ChartOfAccounts chart) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.chart = chart;
  }

  @Override
  @Transactional
  public TrialBalance trialBalance(LocalDate asOf) {
    List<Line> lines = lines(day(asOf));
    BigDecimal debit = Amounts.ZERO;
    BigDecimal credit = Amounts.ZERO;
    for (Line l : lines) {
      debit = debit.add(l.debit());
      credit = credit.add(l.credit());
    }
    return new TrialBalance(day(asOf), lines, Amounts.money(debit), Amounts.money(credit));
  }

  @Override
  @Transactional
  public BalanceSheet balanceSheet(LocalDate asOf) {
    List<Line> lines = lines(day(asOf));
    Section assets = section("Assets", lines, AccountType.ASSET);
    Section liabilities = section("Liabilities", lines, AccountType.LIABILITY);
    Section equity = section("Equity", lines, AccountType.EQUITY);
    BigDecimal earnings =
        Amounts.money(
            total(lines, AccountType.REVENUE).subtract(total(lines, AccountType.EXPENSE)));
    BigDecimal right = Amounts.money(liabilities.total().add(equity.total()).add(earnings));
    return new BalanceSheet(
        day(asOf),
        assets,
        liabilities,
        equity,
        earnings,
        right,
        assets.total().compareTo(right) == 0);
  }

  @Override
  @Transactional
  public VatSummary vat(LocalDate from, LocalDate to) {
    LocalDate start = from == null ? LocalDate.now().withDayOfMonth(1) : from;
    LocalDate end = day(to);
    if (end.isBefore(start)) {
      throw Refusal.invalid("invalid_period", "to is before from.", "to");
    }
    // 2100 Tax payable: credits are VAT charged on sales, debits VAT given back on returns.
    BigDecimal[] totals = {Amounts.ZERO, Amounts.ZERO};
    jdbc.query(
        "select coalesce(sum(l.credit), 0) as credit, coalesce(sum(l.debit), 0) as debit"
            + " from finance.journal_lines l join finance.journal_entries e on e.id = l.entry_id"
            + " where l.account_code = '2100' and e.entry_date between ? and ?",
        row -> {
          totals[0] = row.getBigDecimal("credit");
          totals[1] = row.getBigDecimal("debit");
        },
        start,
        end);
    return new VatSummary(
        start,
        end,
        Amounts.money(totals[0]),
        Amounts.money(totals[1]),
        Amounts.money(totals[0].subtract(totals[1])));
  }

  private List<Line> lines(LocalDate asOf) {
    chart.ensure();
    List<Line> lines = new ArrayList<>();
    jdbc.query(
        "select a.code, a.name, a.type,"
            + " coalesce(sum(case when e.entry_date <= ? then l.debit end), 0) as debit,"
            + " coalesce(sum(case when e.entry_date <= ? then l.credit end), 0) as credit"
            + " from finance.accounts a"
            + " left join finance.journal_lines l on l.account_code = a.code"
            + " left join finance.journal_entries e on e.id = l.entry_id"
            + " group by a.code, a.name, a.type order by a.code",
        row -> {
          AccountType type = AccountType.valueOf(row.getString("type"));
          BigDecimal debit = Amounts.money(row.getBigDecimal("debit"));
          BigDecimal credit = Amounts.money(row.getBigDecimal("credit"));
          BigDecimal balance =
              type == AccountType.ASSET || type == AccountType.EXPENSE
                  ? debit.subtract(credit)
                  : credit.subtract(debit);
          lines.add(
              new Line(
                  row.getString("code"),
                  row.getString("name"),
                  type,
                  debit,
                  credit,
                  Amounts.money(balance)));
        },
        asOf,
        asOf);
    return lines;
  }

  private static Section section(String title, List<Line> lines, AccountType type) {
    List<Line> of = lines.stream().filter(l -> l.type() == type).toList();
    return new Section(title, of, total(lines, type));
  }

  private static BigDecimal total(List<Line> lines, AccountType type) {
    return Amounts.money(
        lines.stream()
            .filter(l -> l.type() == type)
            .map(Line::balance)
            .reduce(BigDecimal.ZERO, BigDecimal::add));
  }

  private static LocalDate day(LocalDate value) {
    return value == null ? LocalDate.now() : value;
  }
}
