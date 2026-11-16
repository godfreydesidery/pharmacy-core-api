package tz.co.pharmacy.modules.tenancy.ports.inbound;

/**
 * TRIAL until the trial ends; ACTIVE while paid up; PAST_DUE after an invoice's due date, during
 * the grace period; SUSPENDED once grace runs out (the tenant is suspended too); CANCELLED.
 */
public enum SubscriptionStatus {
  TRIAL,
  ACTIVE,
  PAST_DUE,
  SUSPENDED,
  CANCELLED
}
