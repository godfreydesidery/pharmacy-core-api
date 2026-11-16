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

/** One payment towards a sale; a sale may be split across methods. */
@Entity
@Table(name = "sale_payments", schema = "sales")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SalePayment extends TenantEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "sale_id", nullable = false)
  private Sale sale;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private PaymentMethod method;

  @Column(nullable = false, precision = 14, scale = 2)
  private BigDecimal amount;

  private String reference;

  /** The tender's currency; {@code amount} is in it. */
  @Column(nullable = false)
  private String currency;

  /** Base units per 1 unit of {@code currency}, locked at the moment of sale. */
  @Column(nullable = false, precision = 24, scale = 10)
  private BigDecimal exchangeRate;

  /** {@code amount} in the base currency. */
  @Column(nullable = false, precision = 14, scale = 2)
  private BigDecimal baseAmount;

  /** The registered payment method used (MPESA, CRDB, …), for reconciliation; null for none. */
  private String channel;

  public SalePayment(
      PaymentMethod method,
      BigDecimal amount,
      String reference,
      String currency,
      BigDecimal exchangeRate,
      BigDecimal baseAmount) {
    this.method = method;
    this.amount = amount;
    this.reference = reference;
    this.currency = currency;
    this.exchangeRate = exchangeRate;
    this.baseAmount = baseAmount;
  }

  public SalePayment through(String channel) {
    this.channel = channel;
    return this;
  }

  void attachTo(Sale sale) {
    this.sale = sale;
  }
}
