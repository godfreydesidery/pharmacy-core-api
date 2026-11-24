package tz.co.pharmacy.platform.persistence;

import java.util.Collection;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The lock on what is still open of a goods receipt (its payable). A supplier payment that settles
 * it and a supplier credit note that lowers it both read the open amount and then take some of it:
 * each holds this lock (a transaction-scoped advisory lock per receipt, taken in uid order so two
 * callers never deadlock) from before they read until they commit, so two of them at once, on any
 * instance, cannot both take the same open amount.
 */
public final class PayableLocks {

  private PayableLocks() {}

  /** Locks the receipts' payables until the current transaction ends. */
  public static void lock(JdbcTemplate jdbc, Collection<UUID> receiptUids) {
    if (receiptUids == null || receiptUids.isEmpty()) {
      return;
    }
    UUID tenant = TenantContext.requireTenant();
    receiptUids.stream()
        .distinct()
        .sorted()
        .forEach(
            uid ->
                jdbc.queryForObject(
                    "select 1 from (select pg_advisory_xact_lock(hashtextextended(?, 0))) held",
                    Integer.class,
                    tenant + ":goods-receipt-payable:" + uid));
  }
}
