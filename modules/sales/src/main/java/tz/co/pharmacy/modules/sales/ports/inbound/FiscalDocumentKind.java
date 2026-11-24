package tz.co.pharmacy.modules.sales.ports.inbound;

/**
 * A fiscal document's kind: a sale's receipt, a return's credit note, a sales debit note (STD-2), a
 * branch's Z report.
 */
public enum FiscalDocumentKind {
  RECEIPT,
  CREDIT_NOTE,
  DEBIT_NOTE,
  Z_REPORT
}
