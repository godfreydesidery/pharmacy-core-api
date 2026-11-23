package tz.co.pharmacy.modules.sales.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.modules.sales.ports.inbound.PaymentMethod;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/**
 * One share of a return's refund, by the way the sale was paid; {@code reference} is the voucher's
 * code for a VOUCHER share (credited back onto it), else null.
 */
@Entity
@Table(name = "sale_return_refunds", schema = "sales")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SaleReturnRefund extends TenantEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "return_id", nullable = false)
  private SaleReturn saleReturn;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false)
  private PaymentMethod method;

  @Column(nullable = false, updatable = false, precision = 14, scale = 2)
  private BigDecimal amount;

  private String reference;

  public SaleReturnRefund(PaymentMethod method, BigDecimal amount, String reference) {
    this.method = method;
    this.amount = amount;
    this.reference = reference;
  }

  void attachTo(SaleReturn saleReturn) {
    this.saleReturn = saleReturn;
  }
}
