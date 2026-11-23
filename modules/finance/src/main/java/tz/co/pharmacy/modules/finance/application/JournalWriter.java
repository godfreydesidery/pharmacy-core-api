package tz.co.pharmacy.modules.finance.application;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.finance.domain.JournalEntry;
import tz.co.pharmacy.modules.finance.persistence.JournalEntryRepository;
import tz.co.pharmacy.platform.persistence.TenantContext;

/**
 * Writes one journal entry per source document. A transaction-scoped advisory lock on the source
 * serialises two deliveries of the same event, and the existence check then makes the second one
 * post nothing; the unique constraint on (tenant, source type, source uid) backs both.
 *
 * <p>Every line also adds to its account's row of {@code finance.account_daily_balances} (PERF-4),
 * through a trigger in the same transaction. Two postings at once would lock those rows in their
 * own line order and could deadlock, so the writer first locks the entry's rows in account order,
 * in one statement (a new row is inserted at zero; an existing one is only locked). The journal
 * number is taken last (PERF-14): its counter row, the pharmacy's one serialisation point, is
 * locked only from the entry's insert to the commit. Lock order: the source's advisory lock, then
 * the balance rows by account code, then the number series.
 */
@Component
class JournalWriter {

  private final JournalEntryRepository entries;
  private final ChartOfAccounts chart;
  private final JdbcTemplate jdbc;
  private final PeriodLocksImpl periods;

  JournalWriter(
      JournalEntryRepository entries,
      ChartOfAccounts chart,
      DataSource dataSource,
      PeriodLocksImpl periods) {
    this.entries = entries;
    this.chart = chart;
    this.jdbc = new JdbcTemplate(dataSource);
    this.periods = periods;
  }

  /** Posts the lines unless this source was posted already; returns whether it posted. */
  @Transactional(propagation = Propagation.MANDATORY)
  boolean post(
      String sourceType,
      UUID sourceUid,
      String sourceReference,
      String description,
      UUID branchUid,
      LocalDate date,
      List<Postings.Line> lines) {
    if (lines.isEmpty()) {
      return false;
    }
    Postings.balanced(lines);
    LocalDate bookedOn = periods.bookingDate(sourceType, date);
    String key = TenantContext.requireTenant() + ":" + sourceType + ":" + sourceUid;
    jdbc.queryForObject(
        "select 1 from (select pg_advisory_xact_lock(hashtext(?))) as locked", Integer.class, key);
    if (entries.existsBySourceTypeAndSourceUid(sourceType, sourceUid)) {
      return false;
    }
    chart.ensure();
    Map<String, String> names = chart.names();
    lockBalances(lines, branchUid, bookedOn);
    JournalEntry entry =
        new JournalEntry(
            nextNumber(bookedOn),
            bookedOn,
            sourceType,
            sourceUid,
            sourceReference,
            description,
            branchUid);
    for (Postings.Line line : lines) {
      entry.addLine(
          line.accountCode(),
          names.getOrDefault(line.accountCode(), line.accountCode()),
          line.debit(),
          line.credit(),
          line.currency(),
          line.foreignAmount());
    }
    entries.saveAndFlush(entry);
    return true;
  }

  /**
   * Locks (creating at zero) the daily balance rows the lines will add to, in account order: {@code
   * on conflict do update ... where false} locks an existing row without writing it.
   */
  private void lockBalances(List<Postings.Line> lines, UUID branchUid, LocalDate day) {
    TreeSet<String> codes = new TreeSet<>();
    lines.forEach(line -> codes.add(line.accountCode()));
    List<Object> parameters = new ArrayList<>();
    parameters.add(Date.valueOf(day));
    parameters.add(branchUid);
    parameters.addAll(codes);
    jdbc.update(
        "insert into finance.account_daily_balances as b (tenant_uid, account_code, day,"
            + " branch_uid) select platform.current_tenant_uid(), v.code, cast(? as date),"
            + " cast(? as uuid) from (values "
            + String.join(", ", Collections.nCopies(codes.size(), "(?)"))
            + ") as v(code) order by v.code"
            + " on conflict on constraint account_daily_balances_key"
            + " do update set debit = b.debit where false",
        parameters.toArray());
  }

  /** JV-2026-000123: the next number of the booking year's gap-free series, locked to commit. */
  private String nextNumber(LocalDate bookedOn) {
    Long next =
        jdbc.queryForObject(
            "insert into finance.journal_counters as c (tenant_uid, year, last_value)"
                + " values (platform.current_tenant_uid(), ?, 1)"
                + " on conflict (tenant_uid, year) do update set last_value = c.last_value + 1"
                + " returning last_value",
            Long.class,
            bookedOn.getYear());
    return "JV-" + bookedOn.getYear() + "-" + String.format("%06d", next);
  }
}
