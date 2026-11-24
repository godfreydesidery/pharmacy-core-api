package tz.co.pharmacy.modules.tenancy.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.pharmacy.modules.tenancy.domain.Plan;
import tz.co.pharmacy.modules.tenancy.domain.Subscription;
import tz.co.pharmacy.modules.tenancy.domain.SubscriptionInvoice;
import tz.co.pharmacy.modules.tenancy.domain.Tenant;
import tz.co.pharmacy.modules.tenancy.persistence.PlanRepository;
import tz.co.pharmacy.modules.tenancy.persistence.SubscriptionInvoiceRepository;
import tz.co.pharmacy.modules.tenancy.persistence.SubscriptionRepository;
import tz.co.pharmacy.modules.tenancy.persistence.TenantRepository;
import tz.co.pharmacy.modules.tenancy.ports.inbound.BillingCycle;
import tz.co.pharmacy.modules.tenancy.ports.inbound.Entitlements;
import tz.co.pharmacy.modules.tenancy.ports.inbound.PlanFeature;
import tz.co.pharmacy.modules.tenancy.ports.inbound.PlanLimit;
import tz.co.pharmacy.modules.tenancy.ports.inbound.SubscriptionPayments;
import tz.co.pharmacy.modules.tenancy.ports.inbound.SubscriptionStatus;
import tz.co.pharmacy.modules.tenancy.ports.inbound.Subscriptions;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantStatus;
import tz.co.pharmacy.modules.tenancy.ports.outbound.SubscriptionNotices;
import tz.co.pharmacy.modules.tenancy.ports.outbound.TenantUsage;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.persistence.SingleRunner;
import tz.co.pharmacy.platform.persistence.jobs.JobRun;

/**
 * Plans, subscriptions and their invoices. Writes run in short transactions of their own; usage is
 * counted outside any transaction because the counts run as the tenant concerned.
 */
@Service
class SubscriptionsImpl implements Subscriptions, Entitlements, SubscriptionPayments {

  /** The suspension reason this module sets and, on payment, lifts. */
  static final String UNPAID = "Subscription unpaid";

  static final String DUE_TEMPLATE = "tenant.subscription_due";
  static final String LIFECYCLE_JOB = "subscription-lifecycle";
  static final String PAST_DUE_TEMPLATE = "tenant.subscription_past_due";

  private final PlanRepository plans;
  private final SubscriptionRepository subscriptions;
  private final SubscriptionInvoiceRepository invoices;
  private final TenantRepository tenants;
  private final TenantUsage usage;
  private final SubscriptionNotices notices;
  private final TransactionTemplate tx;
  private final SingleRunner runner;
  private final Clock clock;
  private final String trialPlan;
  private final int graceDays;
  private final int invoiceDaysBefore;

  SubscriptionsImpl(
      PlanRepository plans,
      SubscriptionRepository subscriptions,
      SubscriptionInvoiceRepository invoices,
      TenantRepository tenants,
      TenantUsage usage,
      SubscriptionNotices notices,
      PlatformTransactionManager transactions,
      SingleRunner runner,
      Clock clock,
      @Value("${pharmacy.subscriptions.trial-plan:PRO}") String trialPlan,
      @Value("${pharmacy.subscriptions.grace-days:7}") int graceDays,
      @Value("${pharmacy.subscriptions.invoice-days-before:7}") int invoiceDaysBefore) {
    this.plans = plans;
    this.subscriptions = subscriptions;
    this.invoices = invoices;
    this.tenants = tenants;
    this.usage = usage;
    this.notices = notices;
    this.tx = new TransactionTemplate(transactions);
    this.runner = runner;
    this.clock = clock;
    this.trialPlan = trialPlan;
    this.graceDays = graceDays;
    this.invoiceDaysBefore = invoiceDaysBefore;
  }

  // ---------------------------------------------------------------- plans

  @Override
  public List<PlanView> plans() {
    return plans.findAllByOrderBySortOrderAscCodeAsc().stream()
        .map(SubscriptionsImpl::view)
        .toList();
  }

  @Override
  public PlanView savePlan(UUID uid, PlanCommand c) {
    if (c == null) {
      throw Refusal.malformed("The request needs a body.");
    }
    return tx.execute(
        status -> {
          Plan plan;
          if (uid == null) {
            String code = Checks.code(c.code(), "code");
            if (plans.existsByCode(code)) {
              throw Refusal.conflict("duplicate_code", "A plan with code " + code + " exists.");
            }
            plan = new Plan(code);
          } else {
            plan =
                plans
                    .findByUid(uid)
                    .orElseThrow(() -> Refusal.notFound("plan_not_found", "No such plan."));
          }
          String currency = Checks.text(c.currency(), "currency", 3).toUpperCase(Locale.ROOT);
          if (!currency.matches("[A-Z]{3}")) {
            throw Refusal.invalid("invalid_currency", "currency is an ISO 4217 code.", "currency");
          }
          Set<PlanFeature> features = EnumSet.noneOf(PlanFeature.class);
          if (Boolean.TRUE.equals(c.insurance())) features.add(PlanFeature.INSURANCE);
          if (Boolean.TRUE.equals(c.multiCurrency())) features.add(PlanFeature.MULTI_CURRENCY);
          if (Boolean.TRUE.equals(c.integrations())) features.add(PlanFeature.INTEGRATIONS);
          if (Boolean.TRUE.equals(c.advancedReports())) features.add(PlanFeature.ADVANCED_REPORTS);
          plan.change(
              Checks.text(c.name(), "name", 80),
              Checks.optionalText(c.description(), "description", 500),
              currency,
              money(c.priceMonthly(), "priceMonthly"),
              money(c.priceYearly(), "priceYearly"),
              cap(c.maxBranches(), "maxBranches"),
              cap(c.maxUsers(), "maxUsers"),
              cap(c.maxProducts(), "maxProducts"),
              c.smsPerMonth() == null ? null : Math.max(c.smsPerMonth(), 0),
              features,
              c.trialDays() == null ? 30 : Math.max(c.trialDays(), 0),
              c.sortOrder() == null ? 0 : c.sortOrder(),
              c.active() == null || c.active());
          return view(plans.save(plan));
        });
  }

  // --------------------------------------------------------- subscriptions

  /** A new tenant's trial; called by registration in its transaction. */
  void startTrial(UUID tenantUid) {
    if (subscriptions.findByTenantUid(tenantUid).isPresent()) {
      return;
    }
    Plan plan =
        plans
            .findByCode(trialPlan)
            .orElseGet(
                () ->
                    plans.findAllByOrderBySortOrderAscCodeAsc().stream()
                        .reduce((first, second) -> second)
                        .orElseThrow(() -> new IllegalStateException("No subscription plans")));
    subscriptions.save(Subscription.trial(tenantUid, plan, today()));
  }

  @Override
  public SubscriptionView subscription(UUID tenantUid) {
    Subscription s = required(tenantUid);
    List<Usage> used = new ArrayList<>();
    for (PlanLimit limit : PlanLimit.values()) {
      used.add(new Usage(limit, usage.used(tenantUid, limit), s.getPlan().cap(limit)));
    }
    List<InvoiceView> bills =
        invoices.findByTenantUidOrderByIdDesc(tenantUid).stream()
            .map(SubscriptionsImpl::view)
            .toList();
    return new SubscriptionView(
        tenantUid,
        view(s.getPlan()),
        s.getStatus(),
        s.getBillingCycle(),
        s.getPeriodStart(),
        s.getPeriodEnd(),
        s.getTrialEnds(),
        s.getGraceEnds(),
        used,
        bills);
  }

  @Override
  public SubscriptionStatusView status(UUID tenantUid) {
    Subscription s = required(tenantUid);
    LocalDate due =
        invoices
            .findFirstByTenantUidAndStatusOrderByIdAsc(tenantUid, "OPEN")
            .map(SubscriptionInvoice::getDueDate)
            .orElse(null);
    return new SubscriptionStatusView(
        s.getStatus(),
        s.getPlan().getCode(),
        s.getPlan().getName(),
        s.getTrialEnds(),
        due,
        s.getGraceEnds(),
        s.getPlan().features());
  }

  @Override
  public SubscriptionView changePlan(UUID tenantUid, String planCode, BillingCycle cycle) {
    tx.executeWithoutResult(
        status -> {
          Subscription s = required(tenantUid);
          Plan plan =
              plans
                  .findByCode(Checks.text(planCode, "planCode", 40).toUpperCase(Locale.ROOT))
                  .filter(Plan::isActive)
                  .orElseThrow(
                      () -> Refusal.invalid("plan_not_found", "No such active plan.", "planCode"));
          s.changePlan(plan, cycle == null ? s.getBillingCycle() : cycle);
          // An open invoice priced for the old plan is cancelled; the lifecycle issues a new one.
          invoices.findByTenantUidOrderByIdDesc(tenantUid).stream()
              .filter(SubscriptionInvoice::open)
              .forEach(SubscriptionInvoice::voidIt);
        });
    return subscription(tenantUid);
  }

  @Override
  public SubscriptionView extendTrial(UUID tenantUid, int days) {
    if (days < 1 || days > 365) {
      throw Refusal.invalid("invalid_days", "days must be between 1 and 365.", "days");
    }
    tx.executeWithoutResult(
        status -> {
          Subscription s = required(tenantUid);
          LocalDate from =
              s.getStatus() == SubscriptionStatus.TRIAL
                      && s.getTrialEnds() != null
                      && s.getTrialEnds().isAfter(today())
                  ? s.getTrialEnds()
                  : today();
          s.extendTrial(from.plusDays(days));
          invoices.findByTenantUidOrderByIdDesc(tenantUid).stream()
              .filter(SubscriptionInvoice::open)
              .forEach(SubscriptionInvoice::voidIt);
          liftUnpaidSuspension(tenantUid);
        });
    return subscription(tenantUid);
  }

  @Override
  public Overview overview() {
    BigDecimal mrr = BigDecimal.ZERO;
    String currency = "TZS";
    long trials = 0;
    long active = 0;
    long pastDue = 0;
    long suspended = 0;
    for (Subscription s : subscriptions.findAll()) {
      switch (s.getStatus()) {
        case TRIAL -> trials++;
        case ACTIVE -> active++;
        case PAST_DUE -> pastDue++;
        case SUSPENDED -> suspended++;
        default -> {}
      }
      if (s.getStatus() == SubscriptionStatus.ACTIVE
          || s.getStatus() == SubscriptionStatus.PAST_DUE) {
        Plan plan = s.getPlan();
        currency = plan.getCurrency();
        mrr =
            mrr.add(
                s.getBillingCycle() == BillingCycle.YEARLY
                    ? plan.getPriceYearly().divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP)
                    : plan.getPriceMonthly());
      }
    }
    return new Overview(mrr, currency, trials, active, pastDue, suspended, invoices.outstanding());
  }

  // ------------------------------------------------------------ entitlements

  @Override
  public void requireFeature(UUID tenantUid, PlanFeature feature) {
    Plan plan = planOf(tenantUid);
    if (!plan.features().contains(feature)) {
      throw Refusal.forbidden(
          "plan_feature_unavailable",
          "The "
              + plan.getName()
              + " plan does not include "
              + label(feature)
              + ". Upgrade the subscription to use it.");
    }
  }

  @Override
  public void requireRoom(UUID tenantUid, PlanLimit limit) {
    requireRoom(tenantUid, limit, 1);
  }

  @Override
  public void requireRoom(UUID tenantUid, PlanLimit limit, long adding) {
    if (adding <= 0) {
      return;
    }
    Plan plan = planOf(tenantUid);
    Integer cap = plan.cap(limit);
    if (cap == null) {
      return;
    }
    refuseOverCap(plan, cap, limit, usage.used(tenantUid, limit), adding);
  }

  @Override
  public void requireRoomGiven(UUID tenantUid, PlanLimit limit, long used, long adding) {
    if (adding <= 0) {
      return;
    }
    Plan plan = planOf(tenantUid);
    Integer cap = plan.cap(limit);
    if (cap == null) {
      return;
    }
    refuseOverCap(plan, cap, limit, used, adding);
  }

  private static void refuseOverCap(Plan plan, int cap, PlanLimit limit, long used, long adding) {
    if (used + adding > cap) {
      throw Refusal.invalid(
              "plan_limit_reached",
              "The "
                  + plan.getName()
                  + " plan allows "
                  + cap
                  + " "
                  + label(limit)
                  + " and "
                  + used
                  + " are in use"
                  + (adding > 1 ? "; the import would add " + adding : "")
                  + ". Upgrade the subscription to add more.",
              "plan")
          .with("limit", cap)
          .with("plan", plan.getName())
          .with("resource", limit.name())
          .with("used", used);
    }
  }

  // ---------------------------------------------------------------- payments

  @Override
  public InvoiceView recordPayment(
      UUID invoiceUid, BigDecimal amount, String method, String reference, LocalDate paidOn) {
    String how = Checks.text(method, "method", 40);
    String ref = Checks.optionalText(reference, "reference", 120);
    return tx.execute(
        status -> {
          SubscriptionInvoice invoice =
              invoices
                  .findByUid(Checks.required(invoiceUid, "invoiceUid"))
                  .orElseThrow(() -> Refusal.notFound("invoice_not_found", "No such invoice."));
          if (!invoice.open()) {
            throw Refusal.invalid(
                "invoice_not_open",
                "Invoice "
                    + invoice.getNumber()
                    + " is "
                    + invoice.getStatus().toLowerCase(Locale.ROOT)
                    + ".",
                "invoiceUid");
          }
          if (amount != null && amount.compareTo(invoice.getAmount()) < 0) {
            throw Refusal.invalid(
                    "payment_short",
                    "The invoice is "
                        + invoice.getCurrency()
                        + " "
                        + invoice.getAmount().toPlainString()
                        + "; a part payment does not settle it.",
                    "amount")
                .with("paid", amount)
                .with("total", invoice.getAmount())
                .with("currency", invoice.getCurrency());
          }
          invoice.pay(paidOn == null ? today() : paidOn, how, ref);
          Subscription s = required(invoice.getTenantUid());
          s.paid(invoice.getPeriodStart(), invoice.getPeriodEnd());
          liftUnpaidSuspension(invoice.getTenantUid());
          return view(invoice);
        });
  }

  // --------------------------------------------------------------- lifecycle

  /**
   * Every night, on one instance ({@link SingleRunner}): invoices, reminders, past due and
   * suspensions. A pharmacy whose step or notice fails is recorded with the run (and the run is
   * FAILED), instead of the error being dropped.
   */
  @Scheduled(cron = "${pharmacy.subscriptions.lifecycle-cron:0 15 2 * * *}")
  void daily() {
    runner.runAlone(
        LIFECYCLE_JOB,
        run ->
            ExecutionContext.callAs(
                ExecutionContext.Frame.service(LIFECYCLE_JOB, UUID.randomUUID().toString(), null),
                () -> {
                  List<String> done = lifecycle(today(), null, run);
                  run.note(done.size() + " subscription steps");
                  return null;
                }));
  }

  @Override
  public List<String> runLifecycle(LocalDate today) {
    return runLifecycle(today, null);
  }

  @Override
  public List<String> runLifecycle(LocalDate today, UUID tenantUid) {
    return lifecycle(today, tenantUid, new JobRun(LIFECYCLE_JOB));
  }

  private List<String> lifecycle(LocalDate today, UUID tenantUid, JobRun run) {
    LocalDate day = today == null ? today() : today;
    List<String> log = new ArrayList<>();
    List<Subscription> live =
        subscriptions.findByStatusIn(
            List.of(
                SubscriptionStatus.TRIAL, SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE));
    for (Subscription candidate : live) {
      if (tenantUid != null && !tenantUid.equals(candidate.getTenantUid())) {
        continue;
      }
      UUID tenant = candidate.getTenantUid();
      List<Notice> toSend = new ArrayList<>();
      try {
        tx.executeWithoutResult(status -> step(tenant, day, log, toSend));
      } catch (RuntimeException failure) {
        log.add(tenant + ": failed (" + failure.getMessage() + ")");
        run.failed(tenant, "subscription step: " + failure);
        continue;
      }
      for (Notice n : toSend) {
        try {
          notices.notifyAdmins(tenant, n.template(), n.params(), n.dedupKey());
        } catch (RuntimeException failure) {
          log.add(tenant + ": notice not sent (" + failure.getMessage() + ")");
          run.failed(tenant, "notice " + n.template() + " not sent: " + failure);
        }
      }
    }
    return log;
  }

  private record Notice(String template, Map<String, Object> params, String dedupKey) {}

  /** One subscription's step for the day: invoice, remind, past due, suspend. */
  private void step(UUID tenantUid, LocalDate day, List<String> log, List<Notice> toSend) {
    Subscription s = required(tenantUid);
    Tenant tenant = tenants.findByUid(tenantUid).orElse(null);
    if (tenant == null) {
      return;
    }
    SubscriptionInvoice open =
        invoices.findFirstByTenantUidAndStatusOrderByIdAsc(tenantUid, "OPEN").orElse(null);
    if (open == null) {
      if (s.getStatus() == SubscriptionStatus.PAST_DUE) {
        return;
      }
      LocalDate end = s.getPeriodEnd();
      if (day.isBefore(end.minusDays(invoiceDaysBefore))) {
        return;
      }
      LocalDate next =
          s.getBillingCycle() == BillingCycle.YEARLY ? end.plusYears(1) : end.plusMonths(1);
      open =
          invoices.save(
              new SubscriptionInvoice(
                  "SUB-" + String.format(Locale.ROOT, "%06d", invoices.nextNumber()),
                  s,
                  end,
                  next,
                  end));
      log.add(tenant.getCode() + ": invoice " + open.getNumber() + " issued, due " + end);
    }
    long daysLeft = ChronoUnit.DAYS.between(day, open.getDueDate());
    Map<String, Object> params = params(tenant, open);
    if (daysLeft >= 0) {
      String key =
          daysLeft <= 1 ? "due-1" : daysLeft <= 3 ? "due-3" : daysLeft <= 7 ? "due-7" : null;
      if (key != null && !(open.getNumber() + ":" + key).equals(s.getLastReminder())) {
        s.reminded(open.getNumber() + ":" + key);
        toSend.add(
            new Notice(DUE_TEMPLATE, params, "subscription:" + open.getNumber() + ":" + key));
        log.add(tenant.getCode() + ": reminded (" + key + ")");
      }
      return;
    }
    if (s.getStatus() != SubscriptionStatus.PAST_DUE) {
      LocalDate grace = open.getDueDate().plusDays(graceDays);
      s.pastDue(grace);
      Map<String, Object> pastDue = new LinkedHashMap<>(params);
      pastDue.put("graceEnds", grace.toString());
      toSend.add(
          new Notice(PAST_DUE_TEMPLATE, pastDue, "subscription:" + open.getNumber() + ":past-due"));
      log.add(tenant.getCode() + ": past due, grace until " + grace);
      return;
    }
    if (s.getGraceEnds() != null && day.isAfter(s.getGraceEnds())) {
      s.suspended();
      if (tenant.getStatus() == TenantStatus.ACTIVE) {
        tenant.suspend(tz.co.pharmacy.modules.tenancy.ports.inbound.SuspensionKind.UNPAID, UNPAID);
      }
      log.add(tenant.getCode() + ": suspended for non-payment");
    }
  }

  /** Reactivates a tenant this module suspended; a manual suspension for another reason stays. */
  private void liftUnpaidSuspension(UUID tenantUid) {
    tenants
        .findByUid(tenantUid)
        .filter(
            t ->
                t.getStatus() == TenantStatus.SUSPENDED
                    && t.getSuspensionKind()
                        == tz.co.pharmacy.modules.tenancy.ports.inbound.SuspensionKind.UNPAID)
        .ifPresent(Tenant::reactivate);
  }

  @Override
  public void resumeAfterReactivation(UUID tenantUid) {
    // A root admin lifted the suspension by hand: the subscription goes on, or the lifecycle would
    // skip it for good. An unpaid invoice gets a fresh grace period; otherwise it is active.
    tx.executeWithoutResult(
        status ->
            subscriptions
                .findByTenantUid(tenantUid)
                .filter(
                    s ->
                        s.getStatus() == SubscriptionStatus.SUSPENDED
                            || s.getStatus() == SubscriptionStatus.CANCELLED)
                .ifPresent(
                    s -> {
                      boolean owing =
                          invoices.findByTenantUidOrderByIdDesc(tenantUid).stream()
                              .anyMatch(SubscriptionInvoice::open);
                      if (owing) {
                        s.pastDue(today().plusDays(graceDays));
                      } else {
                        s.resume();
                      }
                    }));
  }

  @Override
  public void cancel(UUID tenantUid) {
    tx.executeWithoutResult(
        status -> subscriptions.findByTenantUid(tenantUid).ifPresent(Subscription::cancel));
  }

  // ------------------------------------------------------------------ helpers

  private Subscription required(UUID tenantUid) {
    return subscriptions
        .findByTenantUid(Checks.required(tenantUid, "tenantUid"))
        .orElseThrow(
            () -> Refusal.notFound("subscription_not_found", "The pharmacy has no subscription."));
  }

  /** The tenant's plan; a tenant without a subscription row is treated as on trial. */
  private Plan planOf(UUID tenantUid) {
    return subscriptions
        .findByTenantUid(tenantUid)
        .map(Subscription::getPlan)
        .orElseGet(
            () ->
                plans
                    .findByCode(trialPlan)
                    .orElseThrow(() -> new IllegalStateException("No plan")));
  }

  private static Map<String, Object> params(Tenant tenant, SubscriptionInvoice invoice) {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("pharmacyName", tenant.getName());
    params.put("planName", invoice.getPlanName());
    params.put("number", invoice.getNumber());
    params.put(
        "amount",
        invoice.getCurrency() + " " + String.format(Locale.ENGLISH, "%,.2f", invoice.getAmount()));
    params.put("dueDate", invoice.getDueDate().toString());
    return params;
  }

  private static String label(PlanFeature feature) {
    return switch (feature) {
      case INSURANCE -> "insurance claims";
      case MULTI_CURRENCY -> "more than one currency";
      case INTEGRATIONS -> "integrations and webhooks";
      case ADVANCED_REPORTS -> "the financial reports";
    };
  }

  private static String label(PlanLimit limit) {
    return switch (limit) {
      case BRANCHES -> "branches";
      case USERS -> "users";
      case PRODUCTS -> "products";
      case SMS -> "SMS a month";
    };
  }

  private static BigDecimal money(BigDecimal value, String field) {
    BigDecimal v = Checks.required(value, field);
    if (v.signum() < 0) {
      throw Refusal.invalid("invalid_price", field + " cannot be negative.", field);
    }
    return v.setScale(2, RoundingMode.HALF_UP);
  }

  private static Integer cap(Integer value, String field) {
    if (value != null && value < 1) {
      throw Refusal.invalid(
          "invalid_limit", field + " must be at least 1, or empty for unlimited.", field);
    }
    return value;
  }

  private LocalDate today() {
    return LocalDate.now(clock);
  }

  static PlanView view(Plan p) {
    return new PlanView(
        p.getUid(),
        p.getCode(),
        p.getName(),
        p.getDescription(),
        p.getCurrency(),
        p.getPriceMonthly(),
        p.getPriceYearly(),
        p.getMaxBranches(),
        p.getMaxUsers(),
        p.getMaxProducts(),
        p.getSmsPerMonth(),
        p.features(),
        p.getTrialDays(),
        p.getSortOrder(),
        p.isActive());
  }

  static InvoiceView view(SubscriptionInvoice i) {
    return new InvoiceView(
        i.getUid(),
        i.getNumber(),
        i.getPlanCode(),
        i.getPlanName(),
        i.getBillingCycle(),
        i.getPeriodStart(),
        i.getPeriodEnd(),
        i.getAmount(),
        i.getCurrency(),
        i.getStatus(),
        i.getDueDate(),
        i.getPaidAt(),
        i.getPaymentMethod(),
        i.getPaymentReference());
  }
}
