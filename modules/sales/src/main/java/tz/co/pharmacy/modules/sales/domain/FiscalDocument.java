package tz.co.pharmacy.modules.sales.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.modules.sales.ports.inbound.FiscalDocumentKind;
import tz.co.pharmacy.modules.sales.ports.inbound.FiscalStatus;
import tz.co.pharmacy.platform.persistence.TenantEntity;
import tz.co.pharmacy.platform.persistence.audit.AuditIgnore;

/**
 * A document the tax authority issues for the pharmacy (STD-1): a sale's RECEIPT, a return's
 * CREDIT_NOTE, a branch's Z_REPORT. Written PENDING by the transaction of its sale or return (a Z
 * report: of its day's close); every later change (the lease, the counters, the attempts, the
 * authority's answer, the Z report that closes it) is SQL of its own in the repository, so no stale
 * copy of the row can write it back. Left out of the automatic audit trail: the row is its own
 * record, its attempts and last error included, and nothing deletes it.
 */
@AuditIgnore
@Entity
@Table(name = "fiscal_documents", schema = "sales")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FiscalDocument extends TenantEntity {

  @Column(nullable = false, updatable = false)
  private UUID branchUid;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false)
  private FiscalDocumentKind kind;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false)
  private FiscalStatus status;

  @Column(nullable = false, updatable = false)
  private String provider;

  /** The sale (RECEIPT), the return (CREDIT_NOTE), or a uid named by branch and day (Z). */
  @Column(nullable = false, updatable = false)
  private UUID sourceUid;

  @Column(nullable = false, updatable = false)
  private String sourceNumber;

  /** The sale it belongs to (a credit note's: the sale returned); null for a Z report. */
  @Column(updatable = false)
  private UUID saleUid;

  /** A credit note's: the receipt document it corrects. */
  @Column(updatable = false)
  private UUID originalDocumentUid;

  /** The pharmacy's local day of the document. */
  @Column(nullable = false, updatable = false)
  private LocalDate businessDate;

  @Column(updatable = false)
  private String sellerTin;

  @Column(updatable = false)
  private String sellerVrn;

  @Column(nullable = false, updatable = false)
  private BigDecimal total;

  @Column(nullable = false, updatable = false)
  private BigDecimal taxTotal;

  @Column(nullable = false, updatable = false)
  private BigDecimal standardNet;

  @Column(nullable = false, updatable = false)
  private BigDecimal zeroRatedNet;

  @Column(nullable = false, updatable = false)
  private BigDecimal exemptNet;

  @Column(updatable = false)
  private String receiptNumber;

  @Column(updatable = false)
  private String verificationCode;

  @Column(updatable = false)
  private String qrPayload;

  @Column(updatable = false)
  private Long globalCounter;

  @Column(updatable = false)
  private Integer dailyCounter;

  @Column(updatable = false)
  private Instant issuedAt;

  @Column(nullable = false, updatable = false)
  private int attempts;

  @Column(updatable = false)
  private String lastError;

  @Column(updatable = false)
  private Instant claimedUntil;

  /** The Z report that closed this receipt or credit note. */
  @Column(updatable = false)
  private UUID zReportUid;

  @Column(updatable = false)
  private Integer zReceiptCount;

  @Column(updatable = false)
  private BigDecimal zReceiptTotal;

  @Column(updatable = false)
  private BigDecimal zReceiptTax;

  @Column(updatable = false)
  private Integer zCreditNoteCount;

  @Column(updatable = false)
  private BigDecimal zCreditNoteTotal;

  @Column(updatable = false)
  private BigDecimal zCreditNoteTax;

  /** The amounts of a receipt or credit note: its total and tax, and the net of each category. */
  public record Figures(
      BigDecimal total,
      BigDecimal tax,
      BigDecimal standardNet,
      BigDecimal zeroRatedNet,
      BigDecimal exemptNet) {

    public static final Figures NONE =
        new Figures(
            tz.co.pharmacy.platform.kernel.Amounts.ZERO,
            tz.co.pharmacy.platform.kernel.Amounts.ZERO,
            tz.co.pharmacy.platform.kernel.Amounts.ZERO,
            tz.co.pharmacy.platform.kernel.Amounts.ZERO,
            tz.co.pharmacy.platform.kernel.Amounts.ZERO);
  }

  /** A document due to be issued. */
  public FiscalDocument(
      FiscalDocumentKind kind,
      UUID branchUid,
      String provider,
      UUID sourceUid,
      String sourceNumber,
      UUID saleUid,
      UUID originalDocumentUid,
      LocalDate businessDate,
      String sellerTin,
      String sellerVrn,
      Figures amounts) {
    this.kind = kind;
    this.status = FiscalStatus.PENDING;
    this.branchUid = branchUid;
    this.provider = provider;
    this.sourceUid = sourceUid;
    this.sourceNumber = sourceNumber;
    this.saleUid = saleUid;
    this.originalDocumentUid = originalDocumentUid;
    this.businessDate = businessDate;
    this.sellerTin = sellerTin;
    this.sellerVrn = sellerVrn;
    this.total = money(amounts.total());
    this.taxTotal = money(amounts.tax());
    this.standardNet = money(amounts.standardNet());
    this.zeroRatedNet = money(amounts.zeroRatedNet());
    this.exemptNet = money(amounts.exemptNet());
  }

  private static BigDecimal money(BigDecimal value) {
    return value == null
        ? tz.co.pharmacy.platform.kernel.Amounts.ZERO
        : tz.co.pharmacy.platform.kernel.Amounts.money(value);
  }

  /** The uid a branch's Z report of a day carries as its source: one report per branch and day. */
  public static UUID zSource(UUID branchUid, LocalDate day) {
    return UUID.nameUUIDFromBytes(
        ("fiscal-z:" + branchUid + ":" + day).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
}
