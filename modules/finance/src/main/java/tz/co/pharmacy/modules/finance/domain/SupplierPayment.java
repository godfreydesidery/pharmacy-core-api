package tz.co.pharmacy.modules.finance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/** A payment to a supplier: what it cost in the base currency and what it settled at booking. */
@Getter
@Entity
@Table(schema = "finance", name = "supplier_payments")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SupplierPayment extends TenantEntity {

  private String number;
  private UUID supplierUid;
  private String supplierName;

  @Column(precision = 14, scale = 2)
  private BigDecimal amount;

  private String currency;

  @Column(precision = 24, scale = 10)
  private BigDecimal exchangeRate;

  @Column(precision = 14, scale = 2)
  private BigDecimal baseAmount;

  @Column(precision = 14, scale = 2)
  private BigDecimal bookedBaseAmount;

  @Column(precision = 14, scale = 2)
  private BigDecimal fxDifference;

  private String paymentMethod;
  private String reference;

  /** The settled goods receipts' uids, joined by commas; null when none were named. */
  private String goodsReceiptUids;

  private LocalDate paidOn;
  private UUID branchUid;

  public SupplierPayment(
      String number,
      UUID supplierUid,
      String supplierName,
      BigDecimal amount,
      String currency,
      BigDecimal exchangeRate,
      BigDecimal baseAmount,
      BigDecimal bookedBaseAmount,
      String paymentMethod,
      String reference,
      List<UUID> goodsReceiptUids,
      LocalDate paidOn,
      UUID branchUid) {
    this.number = number;
    this.supplierUid = supplierUid;
    this.supplierName = supplierName;
    this.amount = amount;
    this.currency = currency;
    this.exchangeRate = exchangeRate;
    this.baseAmount = baseAmount;
    this.bookedBaseAmount = bookedBaseAmount;
    this.fxDifference = baseAmount.subtract(bookedBaseAmount);
    this.paymentMethod = paymentMethod;
    this.reference = reference;
    this.goodsReceiptUids =
        goodsReceiptUids == null || goodsReceiptUids.isEmpty()
            ? null
            : String.join(",", goodsReceiptUids.stream().map(UUID::toString).toList());
    this.paidOn = paidOn;
    this.branchUid = branchUid;
  }

  public List<UUID> receiptUids() {
    return goodsReceiptUids == null
        ? List.of()
        : Arrays.stream(goodsReceiptUids.split(",")).map(UUID::fromString).toList();
  }
}
