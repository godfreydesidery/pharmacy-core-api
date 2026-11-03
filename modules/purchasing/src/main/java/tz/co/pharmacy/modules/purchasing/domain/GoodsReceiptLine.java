package tz.co.pharmacy.modules.purchasing.domain;

import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.persistence.TenantEntity;

@Getter
@Entity
@Table(schema = "purchasing", name = "goods_receipt_lines")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GoodsReceiptLine extends TenantEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "goods_receipt_id")
  private GoodsReceipt goodsReceipt;

  private UUID productUid;
  private String productName;
  private String batchNumber;
  private LocalDate expiryDate;
  private int quantity;
  private BigDecimal unitCost;
  private BigDecimal lineTotal;

  @Convert(converter = SerialNumberList.class)
  private List<String> serialNumbers = List.of();

  GoodsReceiptLine(
      GoodsReceipt goodsReceipt,
      UUID productUid,
      String productName,
      String batchNumber,
      LocalDate expiryDate,
      int quantity,
      BigDecimal unitCost,
      List<String> serialNumbers) {
    this.goodsReceipt = goodsReceipt;
    this.serialNumbers = serialNumbers == null ? List.of() : List.copyOf(serialNumbers);
    this.productUid = productUid;
    this.productName = productName;
    this.batchNumber = batchNumber;
    this.expiryDate = expiryDate;
    this.quantity = quantity;
    this.unitCost = Amounts.money(unitCost);
    this.lineTotal = Amounts.times(this.unitCost, quantity);
  }
}
