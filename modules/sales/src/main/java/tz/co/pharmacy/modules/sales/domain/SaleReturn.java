package tz.co.pharmacy.modules.sales.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;
import tz.co.pharmacy.modules.sales.ports.inbound.PaymentMethod;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/** Goods of one sale that came back, and the refund paid for them. */
@Entity
@Table(name = "sale_returns", schema = "sales")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SaleReturn extends TenantEntity {

  @Column(nullable = false, updatable = false)
  private String number;

  @Column(nullable = false, updatable = false)
  private UUID branchUid;

  @Column(nullable = false, updatable = false)
  private UUID saleUid;

  @Column(nullable = false, updatable = false)
  private String saleNumber;

  @Column(nullable = false)
  private String reason;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private PaymentMethod refundMethod;

  @Column(nullable = false, precision = 14, scale = 2)
  private BigDecimal refundTotal;

  /** A refund in a foreign currency the sale was tendered in; null for the base currency. */
  private String refundCurrency;

  private BigDecimal refundForeignAmount;

  /** Base units per 1 unit of {@code refundCurrency}: as tendered at the sale, and today. */
  private BigDecimal saleExchangeRate;

  private BigDecimal refundExchangeRate;

  /** Base paid out − {@code refundTotal}: positive a realised loss, negative a gain. */
  @Column(nullable = false, precision = 14, scale = 2)
  private BigDecimal fxDifference = Amounts.ZERO;

  @OneToMany(mappedBy = "saleReturn", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("id")
  @BatchSize(size = 50)
  private List<SaleReturnLine> lines = new ArrayList<>();

  public SaleReturn(
      String number,
      UUID branchUid,
      UUID saleUid,
      String saleNumber,
      String reason,
      PaymentMethod refundMethod) {
    this.number = number;
    this.branchUid = branchUid;
    this.saleUid = saleUid;
    this.saleNumber = saleNumber;
    this.reason = reason;
    this.refundMethod = refundMethod;
    this.refundTotal = Amounts.ZERO;
  }

  public void addLine(SaleReturnLine line) {
    line.attachTo(this);
    lines.add(line);
    refundTotal = Amounts.money(refundTotal.add(line.getRefund()));
  }

  /** Pays the refund back as {@code foreignAmount} of {@code currency}, once the lines are in. */
  public void refundIn(
      String currency,
      BigDecimal foreignAmount,
      BigDecimal saleRate,
      BigDecimal todayRate,
      BigDecimal basePaidOut) {
    this.refundCurrency = currency;
    this.refundForeignAmount = foreignAmount;
    this.saleExchangeRate = saleRate;
    this.refundExchangeRate = todayRate;
    this.fxDifference = Amounts.money(basePaidOut.subtract(refundTotal));
  }

  /** The base value that left the till: the booked refund plus the exchange difference. */
  public BigDecimal basePaidOut() {
    return Amounts.money(refundTotal.add(fxDifference));
  }
}
