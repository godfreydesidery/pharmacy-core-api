package tz.co.pharmacy.modules.tenancy.ports.inbound;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Subscription plans and each pharmacy's subscription: what it may use (limits and modules), its
 * invoices, and the lifecycle trial → active → past due → suspended → active again on payment.
 */
public interface Subscriptions {

  // ---- plans (root administrator) ----

  List<PlanView> plans();

  PlanView savePlan(UUID uid, PlanCommand command);

  // ---- one tenant's subscription ----

  /** The tenant's subscription, plan, usage against the plan's limits and invoices. */
  SubscriptionView subscription(UUID tenantUid);

  /** A light summary for every user of the tenant: status, banner dates and the modules on. */
  SubscriptionStatusView status(UUID tenantUid);

  /** Root: moves the tenant to another plan and cycle from today. */
  SubscriptionView changePlan(UUID tenantUid, String planCode, BillingCycle cycle);

  /** Root: lengthens (or starts again) the trial by {@code days}. */
  SubscriptionView extendTrial(UUID tenantUid, int days);

  /** Platform overview: monthly recurring revenue and counts by status. */
  Overview overview();

  // ---- lifecycle ----

  /**
   * Issues invoices before a period or trial ends, sends reminders, moves unpaid subscriptions to
   * PAST_DUE and then SUSPENDED. Runs daily; root may run it for a given day. Returns what it did.
   */
  List<String> runLifecycle(LocalDate today);

  /**
   * After a root admin reactivated the pharmacy by hand: a suspended subscription goes on (past due
   * with a fresh grace period when an invoice is unpaid, else active), so the lifecycle keeps
   * invoicing it.
   */
  void resumeAfterReactivation(UUID tenantUid);

  /** As {@link #runLifecycle(LocalDate)} for one tenant only. */
  List<String> runLifecycle(LocalDate today, UUID tenantUid);

  record PlanCommand(
      String code,
      String name,
      String description,
      String currency,
      BigDecimal priceMonthly,
      BigDecimal priceYearly,
      Integer maxBranches,
      Integer maxUsers,
      Integer maxProducts,
      Integer smsPerMonth,
      Boolean insurance,
      Boolean multiCurrency,
      Boolean integrations,
      Boolean advancedReports,
      Integer trialDays,
      Integer sortOrder,
      Boolean active) {}

  record PlanView(
      UUID uid,
      String code,
      String name,
      String description,
      String currency,
      BigDecimal priceMonthly,
      BigDecimal priceYearly,
      Integer maxBranches,
      Integer maxUsers,
      Integer maxProducts,
      Integer smsPerMonth,
      Set<PlanFeature> features,
      int trialDays,
      int sortOrder,
      boolean active) {}

  record Usage(PlanLimit limit, long used, Integer allowed) {}

  record InvoiceView(
      UUID uid,
      String number,
      String planCode,
      String planName,
      BillingCycle cycle,
      LocalDate periodStart,
      LocalDate periodEnd,
      BigDecimal amount,
      String currency,
      String status,
      LocalDate dueDate,
      LocalDate paidAt,
      String paymentMethod,
      String paymentReference) {}

  record SubscriptionView(
      UUID tenantUid,
      PlanView plan,
      SubscriptionStatus status,
      BillingCycle cycle,
      LocalDate periodStart,
      LocalDate periodEnd,
      LocalDate trialEnds,
      LocalDate graceEnds,
      List<Usage> usage,
      List<InvoiceView> invoices) {}

  record SubscriptionStatusView(
      SubscriptionStatus status,
      String planCode,
      String planName,
      LocalDate trialEnds,
      LocalDate dueDate,
      LocalDate graceEnds,
      Set<PlanFeature> features) {}

  record Overview(
      BigDecimal monthlyRecurringRevenue,
      String currency,
      long trials,
      long active,
      long pastDue,
      long suspended,
      BigDecimal outstanding) {}
}
