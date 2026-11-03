package tz.co.pharmacy.modules.inventory.ports.inbound;

/** Why stock was adjusted. */
public enum AdjustmentReason {
  DAMAGE,
  THEFT_LOSS,
  COUNT_CORRECTION,
  EXPIRED_WRITE_OFF,
  SAMPLE,
  OTHER
}
