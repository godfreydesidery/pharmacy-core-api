package tz.co.pharmacy.modules.inventory.ports.inbound;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Batch recalls across the tenant: a quarantined batch stays on hand but is never sold, transferred
 * or used in a transformation until released.
 */
public interface Recalls {

  /** Every batch of the product the tenant has had, newest expiry first, with its stock on hand. */
  List<RecallBatch> batches(UUID productUid);

  /** 404 batch_not_found. */
  RecallBatch batch(UUID batchUid);

  /**
   * 422 reason required. Quarantining a quarantined batch updates its reason. 403
   * branch_not_allowed when some of the batch is at (or travelling to or from) a branch the caller
   * may not work in: the quarantine holds it at every branch.
   */
  RecallBatch quarantine(UUID batchUid, String reason);

  /** 403 branch_not_allowed as for {@link #quarantine}. */
  RecallBatch release(UUID batchUid);

  record RecallBatch(
      UUID uid,
      UUID productUid,
      String productName,
      String batchNumber,
      LocalDate expiryDate,
      /* On hand at every branch of the tenant. */
      long onHand,
      boolean quarantined,
      String quarantineReason,
      Instant quarantinedAt,
      String quarantinedBy) {}
}
