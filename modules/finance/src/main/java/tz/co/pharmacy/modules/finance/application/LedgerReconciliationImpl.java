package tz.co.pharmacy.modules.finance.application;

import java.sql.Array;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.finance.ports.inbound.BalanceMismatch;
import tz.co.pharmacy.modules.finance.ports.inbound.LedgerReconciliation;
import tz.co.pharmacy.modules.finance.ports.inbound.UnpostedDocument;
import tz.co.pharmacy.modules.finance.ports.outbound.LedgerSources;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.kernel.TenantCalendar;

/**
 * Source documents with no journal entry, and the daily balances against the journal (PERF-4). The
 * candidates come from the other modules ({@link LedgerSources}) and finance's own tables
 * (expenses, supplier payments); each type is then looked up in the journal by its unique (tenant,
 * source type, source uid) index, a thousand at a time.
 */
@Service
class LedgerReconciliationImpl implements LedgerReconciliation {

  private static final int CHUNK = 1_000;

  private final LedgerSources sources;
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final Duration grace;
  private final TenantCalendar calendar;

  LedgerReconciliationImpl(
      LedgerSources sources,
      DataSource dataSource,
      Clock clock,
      TenantCalendar calendar,
      @Value("${pharmacy.finance.reconciliation.grace:PT15M}") Duration grace) {
    this.sources = sources;
    this.calendar = calendar;
    this.jdbc = new JdbcTemplate(dataSource);
    this.clock = clock;
    this.grace = grace;
  }

  @Override
  @Transactional(readOnly = true)
  public List<UnpostedDocument> unposted(int days) {
    if (days < 1 || days > 92) {
      throw Refusal.invalid("invalid_days", "days must be 1 to 92.", "days");
    }
    Instant before = clock.instant().minus(grace);
    Instant from = clock.instant().minus(Duration.ofDays(days));
    List<UnpostedDocument> candidates = new ArrayList<>(sources.recorded(from, before));
    candidates.addAll(own(from, before));
    Set<UUID> branches = ExecutionContext.reportBranches();
    Map<String, List<UnpostedDocument>> byType = new LinkedHashMap<>();
    for (UnpostedDocument document : candidates) {
      if (branches == null
          || document.branchUid() == null
          || branches.contains(document.branchUid())) {
        byType.computeIfAbsent(document.sourceType(), type -> new ArrayList<>()).add(document);
      }
    }
    List<UnpostedDocument> unposted = new ArrayList<>();
    byType.forEach(
        (type, documents) -> {
          Set<UUID> posted =
              posted(type, documents.stream().map(UnpostedDocument::sourceUid).toList());
          documents.stream().filter(d -> !posted.contains(d.sourceUid())).forEach(unposted::add);
        });
    unposted.sort(Comparator.comparing(UnpostedDocument::recordedAt));
    return unposted;
  }

  @Override
  @Transactional(readOnly = true)
  public List<BalanceMismatch> balanceMismatches(int days) {
    if (days < 1 || days > 366) {
      throw Refusal.invalid("invalid_days", "days must be 1 to 366.", "days");
    }
    // The booking days of the pharmacy's own calendar: from days - 1 days before its today.
    Date from = Date.valueOf(calendar.today().minusDays(days - 1L));
    // A full join on (account, day, branch); the branch-less key is the zero uid, as a null never
    // joins.
    return jdbc.query(
        "with journal as ("
            + "   select l.account_code, e.entry_date as day,"
            + "     coalesce(e.branch_uid, '00000000-0000-0000-0000-000000000000') as branch_key,"
            + "     e.branch_uid, sum(l.debit) as debit, sum(l.credit) as credit"
            + "   from finance.journal_entries e join finance.journal_lines l on l.entry_id = e.id"
            + "   where e.entry_date >= ? group by 1, 2, 3, 4),"
            + " model as ("
            + "   select account_code, day,"
            + "     coalesce(branch_uid, '00000000-0000-0000-0000-000000000000') as branch_key,"
            + "     branch_uid, debit, credit"
            + "   from finance.account_daily_balances where day >= ?)"
            + " select coalesce(j.account_code, m.account_code) as account_code,"
            + "   coalesce(j.day, m.day) as day, coalesce(j.branch_uid, m.branch_uid) as branch_uid,"
            + "   coalesce(j.debit, 0) as journal_debit, coalesce(j.credit, 0) as journal_credit,"
            + "   coalesce(m.debit, 0) as model_debit, coalesce(m.credit, 0) as model_credit"
            + " from journal j full join model m on m.account_code = j.account_code"
            + "   and m.day = j.day and m.branch_key = j.branch_key"
            + " where coalesce(j.debit, 0) <> coalesce(m.debit, 0)"
            + "   or coalesce(j.credit, 0) <> coalesce(m.credit, 0)"
            + " order by 2, 1, 3",
        (row, i) ->
            new BalanceMismatch(
                row.getString("account_code"),
                row.getDate("day").toLocalDate(),
                row.getObject("branch_uid", UUID.class),
                Amounts.money(row.getBigDecimal("journal_debit")),
                Amounts.money(row.getBigDecimal("journal_credit")),
                Amounts.money(row.getBigDecimal("model_debit")),
                Amounts.money(row.getBigDecimal("model_credit"))),
        from,
        from);
  }

  /** Finance's own documents: each posts in its own transaction, so a gap here is a defect. */
  private List<UnpostedDocument> own(Instant from, Instant before) {
    List<UnpostedDocument> documents = new ArrayList<>();
    jdbc.query(
        "select 'EXPENSE' as source_type, uid, number, branch_uid, created_at from finance.expenses"
            + " where created_at >= ? and created_at < ? and amount <> 0"
            + " union all select 'SUPPLIER_PAYMENT', uid, number, branch_uid, created_at"
            + " from finance.supplier_payments where created_at >= ? and created_at < ?",
        row -> {
          documents.add(
              new UnpostedDocument(
                  row.getString(1),
                  row.getObject(2, UUID.class),
                  row.getString(3),
                  row.getObject(4, UUID.class),
                  row.getTimestamp(5).toInstant()));
        },
        Timestamp.from(from),
        Timestamp.from(before),
        Timestamp.from(from),
        Timestamp.from(before));
    return documents;
  }

  private Set<UUID> posted(String sourceType, List<UUID> uids) {
    Set<UUID> posted = new HashSet<>();
    for (int i = 0; i < uids.size(); i += CHUNK) {
      List<UUID> chunk = uids.subList(i, Math.min(uids.size(), i + CHUNK));
      jdbc.query(
          connection -> {
            Array array = connection.createArrayOf("uuid", chunk.toArray());
            var statement =
                connection.prepareStatement(
                    "select source_uid from finance.journal_entries"
                        + " where source_type = ? and source_uid = any(?)");
            statement.setString(1, sourceType);
            statement.setArray(2, array);
            return statement;
          },
          row -> {
            posted.add(row.getObject(1, UUID.class));
          });
    }
    return posted;
  }
}
