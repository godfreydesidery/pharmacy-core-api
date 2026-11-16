package tz.co.pharmacy.modules.tenancy.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import tz.co.pharmacy.modules.tenancy.ports.inbound.BillingCycle;
import tz.co.pharmacy.platform.persistence.MutableEntity;

/** An invoice for one period of a subscription: OPEN until paid (PAID) or cancelled (VOID). */
@Getter
@Entity
@Table(schema = "tenancy", name = "subscription_invoices")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubscriptionInvoice extends MutableEntity {

  private String number;
  private UUID tenantUid;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "subscription_id")
  private Subscription subscription;

  private String planCode;
  private String planName;

  @Enumerated(EnumType.STRING)
  private BillingCycle billingCycle;

  private LocalDate periodStart;
  private LocalDate periodEnd;
  private BigDecimal amount;
  private String currency;
  private String status;
  private LocalDate dueDate;
  private LocalDate paidAt;
  private String paymentMethod;
  private String paymentReference;

  public SubscriptionInvoice(
      String number,
      Subscription subscription,
      LocalDate periodStart,
      LocalDate periodEnd,
      LocalDate dueDate) {
    Plan plan = subscription.getPlan();
    this.number = number;
    this.tenantUid = subscription.getTenantUid();
    this.subscription = subscription;
    this.planCode = plan.getCode();
    this.planName = plan.getName();
    this.billingCycle = subscription.getBillingCycle();
    this.periodStart = periodStart;
    this.periodEnd = periodEnd;
    this.amount = plan.price(subscription.getBillingCycle());
    this.currency = plan.getCurrency();
    this.status = "OPEN";
    this.dueDate = dueDate;
  }

  public boolean open() {
    return "OPEN".equals(status);
  }

  public void pay(LocalDate on, String method, String reference) {
    this.status = "PAID";
    this.paidAt = on;
    this.paymentMethod = method;
    this.paymentReference = reference;
  }

  public void voidIt() {
    this.status = "VOID";
  }
}
