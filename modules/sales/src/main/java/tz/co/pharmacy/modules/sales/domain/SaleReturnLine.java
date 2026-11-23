package tz.co.pharmacy.modules.sales.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.modules.sales.ports.inbound.ReturnDisposition;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/** Units of one sale line that came back; the refund includes {@code tax}, at {@code cost}. */
@Entity
@Table(name = "sale_return_lines", schema = "sales")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SaleReturnLine extends TenantEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "return_id", nullable = false)
  private SaleReturn saleReturn;

  @Column(nullable = false)
  private UUID saleLineUid;

  @Column(nullable = false)
  private UUID productUid;

  @Column(nullable = false)
  private String productName;

  @Column(nullable = false)
  private UUID batchUid;

  private String batchNumber;

  @Column(nullable = false)
  private int quantity;

  @Column(nullable = false, precision = 14, scale = 2)
  private BigDecimal refund;

  @Column(nullable = false, precision = 14, scale = 2)
  private BigDecimal tax;

  @Column(nullable = false, precision = 14, scale = 2)
  private BigDecimal cost;

  @Convert(converter = SerialNumberText.class)
  private List<String> serialNumbers = List.of();

  /** Where the units went: back on the shelf, written off as damaged, or held in quarantine. */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private ReturnDisposition disposition = ReturnDisposition.SHELF;

  /** Units still held in quarantine: not in stock, not sellable. */
  @Column(nullable = false)
  private int heldQuantity;

  /** A released quarantine: SHELF or DAMAGED, when and by whom. */
  @Enumerated(EnumType.STRING)
  private ReturnDisposition releasedOutcome;

  private Instant releasedAt;
  private String releasedBy;
  private java.util.UUID releasedByUid;

  public SaleReturnLine(
      UUID saleLineUid,
      UUID productUid,
      String productName,
      UUID batchUid,
      String batchNumber,
      int quantity,
      BigDecimal refund,
      BigDecimal tax,
      BigDecimal cost) {
    this.saleLineUid = saleLineUid;
    this.productUid = productUid;
    this.productName = productName;
    this.batchUid = batchUid;
    this.batchNumber = batchNumber;
    this.quantity = quantity;
    this.refund = refund;
    this.tax = tax;
    this.cost = cost;
  }

  void attachTo(SaleReturn saleReturn) {
    this.saleReturn = saleReturn;
  }

  /** Where the units go; in quarantine they are all held. */
  public void dispose(ReturnDisposition disposition) {
    this.disposition = disposition;
    this.heldQuantity = disposition == ReturnDisposition.QUARANTINE ? quantity : 0;
  }

  /** Releases every held unit to {@code outcome} (SHELF or DAMAGED). */
  public void release(ReturnDisposition outcome, Instant at, String by, java.util.UUID byUid) {
    this.heldQuantity = 0;
    this.releasedOutcome = outcome;
    this.releasedAt = at;
    this.releasedBy = by;
    this.releasedByUid = byUid;
  }

  /** The serial-tracked units that came back on this line. */
  public void withSerials(List<String> serialNumbers) {
    this.serialNumbers = serialNumbers == null ? List.of() : List.copyOf(serialNumbers);
  }
}
