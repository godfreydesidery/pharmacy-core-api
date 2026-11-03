package tz.co.pharmacy.platform.persistence;

import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gap-tolerant document numbers per tenant and key, such as {@code INV-HQ} → 123 → "INV-HQ-000123".
 * The sequence row is locked only for the instant of the upsert, so numbering scales with the
 * number of keys, not with the number of tenants.
 */
@Component
public class DocumentNumbers {

  private final JdbcTemplate jdbc;

  public DocumentNumbers(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  /** The next number for {@code key} in the current tenant, inside the caller's transaction. */
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
    return prefix + "-" + String.format("%0" + digits + "d", next(prefix));
  }
}
