package tz.co.pharmacy.platform.crypto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Counts and re-seals the values of a {@link SealedColumn} that the current scope may see: the
 * current pharmacy's rows (row-level security, as the application role), or the platform's own rows
 * when no pharmacy is set. Each call is one short transaction.
 */
@Component
public class SealedValues {

  /** The status's name for values still in plain text (webhook secrets saved before sealing). */
  public static final String PLAIN = "plain";

  /**
   * The status's name for hashes kept before keyed hashes ({@link SealedColumn#keyedHash}): plain
   * SHA-256, which need no key.
   */
  public static final String UNKEYED = "unkeyed";

  private final JdbcTemplate jdbc;
  private final TransactionTemplate writes;
  private final TransactionTemplate reads;
  private final Keyring keyring;

  public SealedValues(
      DataSource dataSource, PlatformTransactionManager transactions, Keyring keyring) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.writes = new TransactionTemplate(transactions);
    this.reads = new TransactionTemplate(transactions);
    this.reads.setReadOnly(true);
    this.keyring = keyring;
  }

  /** How many values name each key id ({@code legacy} for unversioned, {@link #PLAIN}). */
  public Map<String, Long> count(SealedColumn column) {
    String c = column.column();
    if (column.keyedHash()) {
      return countKeyedHashes(column);
    }
    StringBuilder key = new StringBuilder("case when ").append(c).append(" like 'v2:%' then");
    key.append(" split_part(").append(c).append(", ':', 2)");
    List<Object> arguments = new ArrayList<>();
    if (column.plainPrefix() != null) {
      key.append(" when ").append(c).append(" like ? then '").append(PLAIN).append("'");
      arguments.add(like(column.plainPrefix()));
    }
    key.append(" else '").append(KeyringSettings.LEGACY).append("' end");
    String sql =
        "select "
            + key
            + " as key_id, count(*) as n from "
            + column.table()
            + " where "
            + c
            + " is not null"
            + scope(column)
            + " group by 1 order by 1";
    Map<String, Long> counts = new LinkedHashMap<>();
    reads.executeWithoutResult(
        status ->
            jdbc.query(
                sql,
                row -> {
                  counts.put(row.getString("key_id"), row.getLong("n"));
                },
                arguments.toArray()));
    return counts;
  }

  /**
   * Re-seals up to {@code limit} values after row {@code afterId} that are not under the purpose's
   * active key (or still plain), in one transaction. A row another transaction holds is skipped
   * ({@code SKIP LOCKED}) and a row changed meanwhile is left as it is: the next run takes them. A
   * value no key in the keyring opens is counted as unreadable and left.
   */
  public Batch reseal(SealedColumn column, long afterId, int limit) {
    if (column.keyedHash()) {
      // A keyed hash cannot be opened: it stays under its key until the code is used or replaced.
      return new Batch(0, 0, afterId, false);
    }
    String c = column.column();
    String active = keyring.activeKey(column.purpose());
    List<Object> arguments = new ArrayList<>();
    StringBuilder stale = new StringBuilder();
    if (KeyringSettings.LEGACY.equals(active)) {
      // Under the legacy key a value stays unversioned: only v2 values (and plain ones) move.
      stale.append("(").append(c).append(" like 'v2:%'");
    } else {
      stale.append("(").append(c).append(" not like ?");
      arguments.add(Keyring.VERSION + ":" + active + ":%");
    }
    if (column.plainPrefix() != null) {
      stale.append(" or ").append(c).append(" like ?");
      arguments.add(like(column.plainPrefix()));
    }
    stale.append(")");
    String select =
        "select id, "
            + c
            + " as sealed from "
            + column.table()
            + " where "
            + c
            + " is not null"
            + scope(column)
            + " and id > ? and "
            + stale
            + " order by id limit ? for update skip locked";
    List<Object> all = new ArrayList<>();
    all.add(afterId);
    all.addAll(arguments);
    all.add(limit);
    return writes.execute(
        status -> {
          List<Row> rows =
              jdbc.query(
                  select,
                  (result, n) -> new Row(result.getLong("id"), result.getString("sealed")),
                  all.toArray());
          int resealed = 0;
          int unreadable = 0;
          long last = afterId;
          for (Row row : rows) {
            last = row.id();
            String fresh;
            try {
              fresh =
                  column.plainPrefix() != null && row.sealed().startsWith(column.plainPrefix())
                      ? keyring.seal(column.purpose(), row.sealed())
                      : keyring.reseal(column.purpose(), row.sealed());
            } catch (IllegalStateException cannotOpen) {
              unreadable++;
              continue;
            }
            resealed +=
                jdbc.update(
                    "update "
                        + column.table()
                        + " set "
                        + c
                        + " = ? where id = ? and "
                        + c
                        + " = ?",
                    fresh,
                    row.id(),
                    row.sealed());
          }
          return new Batch(resealed, unreadable, last, rows.size() == limit);
        });
  }

  private Map<String, Long> countKeyedHashes(SealedColumn column) {
    String c = column.column();
    String sql =
        "select case when "
            + c
            + " like '"
            + Keyring.MAC_VERSION
            + ":%' then split_part("
            + c
            + ", ':', 2) else '"
            + UNKEYED
            + "' end as key_id, count(*) as n from "
            + column.table()
            + " where "
            + c
            + " is not null"
            + scope(column)
            + " group by 1 order by 1";
    Map<String, Long> counts = new LinkedHashMap<>();
    reads.executeWithoutResult(
        status ->
            jdbc.query(
                sql,
                row -> {
                  counts.put(row.getString("key_id"), row.getLong("n"));
                }));
    return counts;
  }

  private static String scope(SealedColumn column) {
    return column.tenantScoped()
        ? " and tenant_uid is not distinct from platform.current_tenant_uid()"
        : "";
  }

  private static String like(String prefix) {
    return prefix.replace("_", "\\_") + "%";
  }

  private record Row(long id, String sealed) {}

  /**
   * One batch's outcome: values re-sealed, values no key opens, the last row looked at, and whether
   * there may be more.
   */
  public record Batch(int resealed, int unreadable, long lastId, boolean more) {}
}
