package tz.co.pharmacy.modules.receivables.ports.inbound;

/**
 * What moved an account. Debits (CHARGE, CREDIT_APPLIED) raise the balance; credits (PAYMENT,
 * CREDIT_NOTE, WRITE_OFF) lower it. CREDIT_APPLIED is account credit spent at the POS.
 */
public enum EntryType {
  CHARGE,
  PAYMENT,
  CREDIT_NOTE,
  WRITE_OFF,
  ADJUSTMENT,
  CREDIT_APPLIED
}
