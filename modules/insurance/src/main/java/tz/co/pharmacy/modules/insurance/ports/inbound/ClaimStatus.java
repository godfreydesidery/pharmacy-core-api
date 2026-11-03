package tz.co.pharmacy.modules.insurance.ports.inbound;

/**
 * A claim's life: raised as DRAFT, SUBMITTED in a batch, adjudicated, PAID; VOIDED when its sale is
 * fully returned before submission.
 */
public enum ClaimStatus {
  DRAFT,
  SUBMITTED,
  APPROVED,
  PARTIALLY_APPROVED,
  REJECTED,
  PAID,
  VOIDED
}
