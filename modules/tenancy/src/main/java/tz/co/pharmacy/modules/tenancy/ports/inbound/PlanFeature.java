package tz.co.pharmacy.modules.tenancy.ports.inbound;

/** A module a plan may or may not include. */
public enum PlanFeature {
  /** Insurance payers, members, claims, batches and remittances. */
  INSURANCE,
  /** Accepting currencies besides the base one. */
  MULTI_CURRENCY,
  /** Webhooks and other integrations. */
  INTEGRATIONS,
  /** Trial balance, balance sheet, VAT, stock valuation and cashier reports. */
  ADVANCED_REPORTS
}
