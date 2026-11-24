package tz.co.pharmacy.modules.sales.ports.inbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A fiscal document (STD-1). {@code receiptNumber}, {@code verificationCode} and {@code qrPayload}
 * are the authority's, set once ISSUED; {@code globalCounter} and {@code dailyCounter} are the
 * branch's counters, taken when it was first sent. {@code sellerTin} and {@code sellerVrn} are the
 * pharmacy's as sent. A Z report's own figures are in {@code z}; it is null for the others.
 */
public record FiscalDocumentView(
    UUID uid,
    FiscalDocumentKind kind,
    FiscalStatus status,
    String provider,
    UUID branchUid,
    UUID sourceUid,
    String sourceNumber,
    UUID saleUid,
    LocalDate businessDate,
    String sellerTin,
    String sellerVrn,
    BigDecimal total,
    BigDecimal taxTotal,
    String receiptNumber,
    String verificationCode,
    String qrPayload,
    Long globalCounter,
    Integer dailyCounter,
    Instant issuedAt,
    int attempts,
    String lastError,
    Instant createdAt,
    ZFigures z) {

  /** What a Z report closed: its receipts and credit notes, their totals, and the net by VAT. */
  public record ZFigures(
      int receipts,
      BigDecimal receiptsTotal,
      BigDecimal receiptsTax,
      int creditNotes,
      BigDecimal creditNotesTotal,
      BigDecimal creditNotesTax,
      BigDecimal standardNet,
      BigDecimal zeroRatedNet,
      BigDecimal exemptNet) {}
}
