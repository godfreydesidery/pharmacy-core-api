package tz.co.pharmacy.modules.sales.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
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

  /** The serial-tracked units that came back on this line. */
  public void withSerials(List<String> serialNumbers) {
    this.serialNumbers = serialNumbers == null ? List.of() : List.copyOf(serialNumbers);
  }
}
