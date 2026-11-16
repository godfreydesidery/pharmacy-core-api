package tz.co.pharmacy.modules.tenancy.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.modules.tenancy.ports.inbound.BillingCycle;
import tz.co.pharmacy.modules.tenancy.ports.inbound.SubscriptionStatus;
import tz.co.pharmacy.platform.persistence.MutableEntity;

/** A tenant's subscription: its plan, status and the period it is paid (or on trial) for. */
@Getter
@Entity
@Table(schema = "tenancy", name = "subscriptions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Subscription extends MutableEntity {

  private UUID tenantUid;

  @ManyToOne(fetch = FetchType.EAGER, optional = false)
  @JoinColumn(name = "plan_id")
  private Plan plan;

  @Enumerated(EnumType.STRING)
  private SubscriptionStatus status;

  @Enumerated(EnumType.STRING)
  private BillingCycle billingCycle;

  private LocalDate periodStart;
  private LocalDate periodEnd;
  private LocalDate trialEnds;
  private LocalDate graceEnds;
  private String lastReminder;

  /** A trial of {@code plan} from {@code today} for the plan's trial days. */
  public static Subscription trial(UUID tenantUid, Plan plan, LocalDate today) {
    Subscription s = new Subscription();
    s.tenantUid = tenantUid;
    s.plan = plan;
    s.status = SubscriptionStatus.TRIAL;
    s.billingCycle = BillingCycle.MONTHLY;
    s.periodStart = today;
    s.trialEnds = today.plusDays(plan.getTrialDays());
    s.periodEnd = s.trialEnds;
    return s;
  }

  public void changePlan(Plan plan, BillingCycle cycle) {
    this.plan = plan;
    this.billingCycle = cycle;
  }

  public void extendTrial(LocalDate until) {
    this.status = SubscriptionStatus.TRIAL;
    this.trialEnds = until;
    this.periodEnd = until;
    this.graceEnds = null;
    this.lastReminder = null;
  }

  /** Paid for {@code start}..{@code end}: active again, grace cleared. */
  public void paid(LocalDate start, LocalDate end) {
    this.status = SubscriptionStatus.ACTIVE;
    this.periodStart = start;
    this.periodEnd = end;
    this.trialEnds = null;
    this.graceEnds = null;
    this.lastReminder = null;
  }

  public void pastDue(LocalDate graceEnds) {
    this.status = SubscriptionStatus.PAST_DUE;
    this.graceEnds = graceEnds;
  }

  public void suspended() {
    this.status = SubscriptionStatus.SUSPENDED;
  }

  public void reminded(String key) {
    this.lastReminder = key;
  }
}
