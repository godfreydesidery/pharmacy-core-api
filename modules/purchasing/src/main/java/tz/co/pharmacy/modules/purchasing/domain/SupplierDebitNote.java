package tz.co.pharmacy.modules.purchasing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/** A supplier's debit note on a goods receipt (STD-2): more owed, in the base currency. */
@Entity
@Table(name = "supplier_debit_notes", schema = "purchasing")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SupplierDebitNote extends TenantEntity {

  @Column(nullable = false, updatable = false)
  private String number;

  @Column(nullable = false, updatable = false)
  private UUID branchUid;

  @Column(nullable = false, updatable = false)
  private UUID goodsReceiptUid;

  @Column(nullable = false, updatable = false)
  private String goodsReceiptNumber;

  @Column(nullable = false, updatable = false)
  private UUID supplierUid;

  @Column(nullable = false, updatable = false)
  private String supplierName;

  @Column(updatable = false)
  private String supplierReference;

  @Column(nullable = false, updatable = false)
  private String reason;

  @Column(nullable = false, updatable = false)
  private BigDecimal net;

  @Column(nullable = false, updatable = false)
  private BigDecimal vat;

  @Column(nullable = false, updatable = false)
  private BigDecimal total;

  public SupplierDebitNote(
      String number,
      GoodsReceipt receipt,
      String supplierReference,
      String reason,
      BigDecimal net,
      BigDecimal vat) {
    this.number = number;
    this.branchUid = receipt.getBranchUid();
    this.goodsReceiptUid = receipt.getUid();
    this.goodsReceiptNumber = receipt.getNumber();
    this.supplierUid = receipt.getSupplierUid();
    this.supplierName = receipt.getSupplierName();
    this.supplierReference = supplierReference;
    this.reason = reason;
    this.net = net;
    this.vat = vat;
    this.total = net.add(vat);
  }
}
