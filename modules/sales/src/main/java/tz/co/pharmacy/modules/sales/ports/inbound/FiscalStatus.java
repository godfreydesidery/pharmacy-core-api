package tz.co.pharmacy.modules.sales.ports.inbound;

/**
 * Where a fiscal document is: PENDING until the tax authority issues it (ISSUED) or refuses it for
 * good (FAILED; someone may send it again).
 */
public enum FiscalStatus {
  PENDING,
  ISSUED,
  FAILED
}
