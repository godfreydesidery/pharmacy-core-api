package tz.co.pharmacy.modules.purchasing.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/** One batch returned, at the batch's unit cost in the base currency. */
@Getter
@Entity
@Table(schema = "purchasing", name = "supplier_return_lines")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SupplierReturnLine extends TenantEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "supplier_return_id")
  private SupplierReturn supplierReturn;

  private UUID productUid;
  private String productName;
  private UUID batchUid;
  private String batchNumber;
  private LocalDate expiryDate;
  private int quantity;
  private BigDecimal unitCost;
  private BigDecimal lineTotal;

  public SupplierReturnLine(
      UUID productUid,
      String productName,
      UUID batchUid,
      String batchNumber,
      LocalDate expiryDate,
      int quantity,
      BigDecimal unitCost) {
    this.productUid = productUid;
    this.productName = productName;
    this.batchUid = batchUid;
    this.batchNumber = batchNumber;
    this.expiryDate = expiryDate;
    this.quantity = quantity;
    this.unitCost = Amounts.money(unitCost);
    this.lineTotal = Amounts.money(this.unitCost.multiply(BigDecimal.valueOf(quantity)));
  }

  void attachTo(SupplierReturn supplierReturn) {
    this.supplierReturn = supplierReturn;
  }
}
