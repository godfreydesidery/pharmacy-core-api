package tz.co.pharmacy.modules.sales.ports.inbound;

/** A fiscal document's kind: a sale's receipt, a return's credit note, a branch's Z report. */
public enum FiscalDocumentKind {
  RECEIPT,
  CREDIT_NOTE,
  Z_REPORT
}
