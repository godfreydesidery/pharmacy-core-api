package tz.co.pharmacy.modules.finance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/** What one supplier payment settled of one goods receipt, in the receipt's currency. */
@Getter
@Entity
@Table(schema = "finance", name = "supplier_payment_allocations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SupplierPaymentAllocation extends TenantEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "supplier_payment_id", nullable = false)
  private SupplierPayment payment;

  private UUID goodsReceiptUid;
  private String currency;

  @Column(precision = 14, scale = 2)
  private BigDecimal amount;

  SupplierPaymentAllocation(
      SupplierPayment payment, UUID goodsReceiptUid, String currency, BigDecimal amount) {
    this.payment = payment;
    this.goodsReceiptUid = goodsReceiptUid;
    this.currency = currency;
    this.amount = amount;
  }
}
