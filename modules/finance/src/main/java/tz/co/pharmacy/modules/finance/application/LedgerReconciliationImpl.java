package tz.co.pharmacy.modules.finance.application;

import java.sql.Array;
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
import tz.co.pharmacy.modules.finance.ports.inbound.LedgerReconciliation;
import tz.co.pharmacy.modules.finance.ports.inbound.UnpostedDocument;
import tz.co.pharmacy.modules.finance.ports.outbound.LedgerSources;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * Source documents with no journal entry. The candidates come from the other modules ({@link
 * LedgerSources}) and finance's own tables (expenses, supplier payments); each type is then looked
 * up in the journal by its unique (tenant, source type, source uid) index, a thousand at a time.
 */
@Service
class LedgerReconciliationImpl implements LedgerReconciliation {

  private static final int CHUNK = 1_000;

  private final LedgerSources sources;
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final Duration grace;

  LedgerReconciliationImpl(
      LedgerSources sources,
      DataSource dataSource,
      Clock clock,
      @Value("${pharmacy.finance.reconciliation.grace:PT15M}") Duration grace) {
    this.sources = sources;
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
