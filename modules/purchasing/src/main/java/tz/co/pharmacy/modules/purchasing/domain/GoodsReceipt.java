package tz.co.pharmacy.modules.purchasing.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/** Goods received at a branch from a supplier, optionally against a purchase order. */
@Getter
@Entity
@Table(schema = "purchasing", name = "goods_receipts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GoodsReceipt extends TenantEntity {

  private String number;
  private UUID branchUid;
  private UUID purchaseOrderUid;
  private String purchaseOrderNumber;
  private UUID supplierUid;
  private String supplierName;
  private String supplierInvoiceNumber;
  private String note;
  private BigDecimal total;

  /** The document currency (ISO 4217); amounts and line costs are in it. */
  private String currency;

  /** Base units per 1 document unit, locked when the document was made. */
  @jakarta.persistence.Column(precision = 24, scale = 10)
  private BigDecimal exchangeRate;

  /** {@code total} in the tenant's base currency at {@code exchangeRate}. */
  private BigDecimal baseTotal;

  /** Prices the document in {@code currency} at {@code exchangeRate} base units per unit. */
  public void priceIn(String currency, BigDecimal exchangeRate) {
    this.currency = currency;
    this.exchangeRate = exchangeRate;
  }

  /** Records the base total, computed by the caller with the base currency's rounding. */
  public void settleBase(BigDecimal baseTotal) {
    this.baseTotal = baseTotal;
  }

  @OneToMany(mappedBy = "goodsReceipt", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("id")
  private List<GoodsReceiptLine> lines = new ArrayList<>();

  public GoodsReceipt(
      String number,
      UUID branchUid,
      PurchaseOrder order,
      UUID supplierUid,
      String supplierName,
      String supplierInvoiceNumber,
      String note) {
    this.number = number;
    this.branchUid = branchUid;
    this.purchaseOrderUid = order == null ? null : order.getUid();
    this.purchaseOrderNumber = order == null ? null : order.getNumber();
    this.supplierUid = supplierUid;
    this.supplierName = supplierName;
    this.supplierInvoiceNumber = supplierInvoiceNumber;
    this.note = note;
    this.total = Amounts.ZERO;
  }

  public void addLine(
      UUID productUid,
      String productName,
      String batchNumber,
      LocalDate expiryDate,
      int quantity,
      BigDecimal unitCost,
      List<String> serialNumbers) {
    GoodsReceiptLine line =
        new GoodsReceiptLine(
            this,
            productUid,
            productName,
            batchNumber,
            expiryDate,
            quantity,
            unitCost,
            serialNumbers);
    lines.add(line);
    total = Amounts.money(total.add(line.getLineTotal()));
  }
}
