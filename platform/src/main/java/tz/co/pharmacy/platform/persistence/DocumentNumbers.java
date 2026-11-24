package tz.co.pharmacy.platform.persistence;

import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Document numbers per tenant and key, such as {@code INV-HQ} → 123 → "INV-HQ-000123", without gaps
 * and without duplicates.
 *
 * <p>The number is an upsert of the key's row in {@code platform.document_sequences}, inside the
 * caller's transaction. That row stays locked until the caller's transaction commits or rolls back:
 * a second transaction taking the same key (another till of the same branch) waits for the first to
 * end. A rollback takes the number back with it, so the numbers a key hands out run without gaps;
 * the price is that numbering is serial per key (per branch for sales), and the caller must take
 * the number late in a transaction that does database work only: never with a payment provider, a
 * tax authority or any other call out still to come (PERF-1). Different keys, and different
 * tenants, never wait for each other.
 */
@Component
public class DocumentNumbers {

  private final JdbcTemplate jdbc;

  public DocumentNumbers(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  /**
   * The next number for {@code key} in the current tenant, inside the caller's transaction, whose
   * key row it locks until that transaction ends.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public long next(String key) {
    return jdbc.queryForObject(
        "insert into platform.document_sequences (tenant_uid, sequence_key, last_value)"
            + " values (platform.current_tenant_uid(), ?, 1)"
            + " on conflict (tenant_uid, sequence_key)"
            + " do update set last_value = platform.document_sequences.last_value + 1"
            + " returning last_value",
        Long.class,
        key);
  }

  /** The next formatted number, such as {@code next("INV-HQ", 6)} = "INV-HQ-000123". */
  @Transactional(propagation = Propagation.MANDATORY)
  public String format(String prefix, int digits) {
    return format(prefix, prefix, digits);
  }

  /**
   * The next number of {@code series}, shown after {@code prefix}: {@code format("sale:HQ",
   * "INV-HQ", 6)} = "INV-HQ-000124". The series is counted, not the prefix, so a document whose
   * prefix the pharmacy chooses (its invoice prefix) carries on counting when the prefix changes
   * instead of starting again at 1 (STD-4).
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public String format(String series, String prefix, int digits) {
    return prefix + "-" + String.format("%0" + digits + "d", next(series));
  }
}
