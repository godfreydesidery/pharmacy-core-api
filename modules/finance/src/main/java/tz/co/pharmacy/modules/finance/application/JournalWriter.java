package tz.co.pharmacy.modules.finance.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.finance.domain.JournalEntry;
import tz.co.pharmacy.modules.finance.persistence.JournalEntryRepository;
import tz.co.pharmacy.platform.persistence.DocumentNumbers;
import tz.co.pharmacy.platform.persistence.TenantContext;

/**
 * Writes one journal entry per source document. A transaction-scoped advisory lock on the source
 * serialises two deliveries of the same event, and the existence check then makes the second one
 * post nothing; the unique constraint on (tenant, source type, source uid) backs both.
 */
@Component
class JournalWriter {

  private final JournalEntryRepository entries;
  private final ChartOfAccounts chart;
  private final DocumentNumbers numbers;
  private final JdbcTemplate jdbc;
  private final PeriodLocksImpl periods;

  JournalWriter(
      JournalEntryRepository entries,
      ChartOfAccounts chart,
      DocumentNumbers numbers,
      DataSource dataSource,
      PeriodLocksImpl periods) {
    this.entries = entries;
    this.chart = chart;
    this.numbers = numbers;
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
    JournalEntry entry =
        new JournalEntry(
            numbers.format("JV", 6),
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
    entries.save(entry);
    return true;
  }
}
