package tz.co.pharmacy.delivery.api.v1.platform;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.modules.access.ports.inbound.Permissions;
import tz.co.pharmacy.modules.tenancy.ports.inbound.BillingCycle;
import tz.co.pharmacy.modules.tenancy.ports.inbound.SubscriptionPayments;
import tz.co.pharmacy.modules.tenancy.ports.inbound.Subscriptions;
import tz.co.pharmacy.modules.tenancy.ports.inbound.Subscriptions.InvoiceView;
import tz.co.pharmacy.modules.tenancy.ports.inbound.Subscriptions.Overview;
import tz.co.pharmacy.modules.tenancy.ports.inbound.Subscriptions.PlanCommand;
import tz.co.pharmacy.modules.tenancy.ports.inbound.Subscriptions.PlanView;
import tz.co.pharmacy.modules.tenancy.ports.inbound.Subscriptions.SubscriptionStatusView;
import tz.co.pharmacy.modules.tenancy.ports.inbound.Subscriptions.SubscriptionView;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * Subscription plans and pharmacies' subscriptions: plans, tenants' subscriptions, invoices and
 * payments for the root administrator; the pharmacy's own subscription for its members.
 */
@RestController
@RequestMapping("/api/v1")
public class SubscriptionHandler {

  private final Subscriptions subscriptions;
  private final SubscriptionPayments payments;
  private final tz.co.pharmacy.delivery.edge.EdgeAccessCache accessCache;

  public SubscriptionHandler(
      Subscriptions subscriptions,
      SubscriptionPayments payments,
      tz.co.pharmacy.delivery.edge.EdgeAccessCache accessCache) {
    this.subscriptions = subscriptions;
    this.payments = payments;
    this.accessCache = accessCache;
  }

  // ---------------------------------------------------------------- platform

  @GetMapping("/platform/plans")
  @Access(scope = AccessScope.PLATFORM, permission = Permissions.PLATFORM_MANAGE)
  public ApiResponse<List<PlanView>> plans() {
    return ApiResponse.ok(subscriptions.plans(), "Plans retrieved");
  }

  @PostMapping("/platform/plans")
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.PLATFORM, permission = Permissions.PLATFORM_MANAGE)
  public ApiResponse<PlanView> createPlan(@RequestBody PlanCommand body) {
    return ApiResponse.created(subscriptions.savePlan(null, body), "Plan created");
  }

  @PutMapping("/platform/plans/{uid}")
  @Access(scope = AccessScope.PLATFORM, permission = Permissions.PLATFORM_MANAGE)
  public ApiResponse<PlanView> changePlan(@PathVariable UUID uid, @RequestBody PlanCommand body) {
    return ApiResponse.ok(subscriptions.savePlan(uid, body), "Plan saved");
  }

  @GetMapping("/platform/subscriptions/overview")
  @Access(scope = AccessScope.PLATFORM, permission = Permissions.PLATFORM_MANAGE)
  public ApiResponse<Overview> overview() {
    return ApiResponse.ok(subscriptions.overview(), "Subscription overview retrieved");
  }

  @GetMapping("/platform/tenants/{tenantUid}/subscription")
  @Access(scope = AccessScope.PLATFORM, permission = Permissions.PLATFORM_MANAGE)
  public ApiResponse<SubscriptionView> tenantSubscription(@PathVariable UUID tenantUid) {
    return ApiResponse.ok(subscriptions.subscription(tenantUid), "Subscription retrieved");
  }

  public record ChangePlanRequest(String planCode, String cycle) {}

  @PutMapping("/platform/tenants/{tenantUid}/subscription")
  @Access(scope = AccessScope.PLATFORM, permission = Permissions.PLATFORM_MANAGE)
  public ApiResponse<SubscriptionView> changeTenantPlan(
      @PathVariable UUID tenantUid, @RequestBody ChangePlanRequest body) {
    if (body == null) {
      throw Refusal.malformed("The request needs a body.");
    }
    BillingCycle cycle =
        body.cycle() == null || body.cycle().isBlank()
            ? null
            : Checks.oneOf(BillingCycle.class, body.cycle(), "cycle");
    SubscriptionView changed = subscriptions.changePlan(tenantUid, body.planCode(), cycle);
    // A plan change may suspend or reactivate the pharmacy (PERF-9: its cached access goes).
    accessCache.forgetTenant(tenantUid);
    return ApiResponse.ok(changed, "Subscription changed");
  }

  public record ExtendTrialRequest(Integer days) {}

  @PostMapping("/platform/tenants/{tenantUid}/subscription/extend-trial")
  @Access(scope = AccessScope.PLATFORM, permission = Permissions.PLATFORM_MANAGE)
  public ApiResponse<SubscriptionView> extendTrial(
      @PathVariable UUID tenantUid, @RequestBody ExtendTrialRequest body) {
    int days = body == null || body.days() == null ? 0 : body.days();
    SubscriptionView extended = subscriptions.extendTrial(tenantUid, days);
    accessCache.forgetTenant(tenantUid);
    return ApiResponse.ok(extended, "Trial extended");
  }

  public record PaymentRequest(
      BigDecimal amount, String method, String reference, LocalDate paidOn) {}

  @PostMapping("/platform/subscriptions/invoices/{invoiceUid}/payments")
  @Access(scope = AccessScope.PLATFORM, permission = Permissions.PLATFORM_MANAGE)
  public ApiResponse<InvoiceView> recordPayment(
      @PathVariable UUID invoiceUid, @RequestBody PaymentRequest body) {
    if (body == null) {
      throw Refusal.malformed("The request needs a body.");
    }
    InvoiceView paid =
        payments.recordPayment(
            invoiceUid, body.amount(), body.method(), body.reference(), body.paidOn());
    // A paid invoice lifts an unpaid suspension at once, on every instance.
    accessCache.forgetAll();
    return ApiResponse.ok(paid, "Payment recorded");
  }

  public record RunRequest(LocalDate asOf, UUID tenantUid) {}

  /** Runs the daily lifecycle now: as of another day ({@code asOf}), for one tenant if named. */
  @PostMapping("/platform/subscriptions/run")
  @Access(scope = AccessScope.PLATFORM, permission = Permissions.PLATFORM_MANAGE)
  public ApiResponse<List<String>> run(@RequestBody(required = false) RunRequest body) {
    List<String> ran =
        subscriptions.runLifecycle(
            body == null ? null : body.asOf(), body == null ? null : body.tenantUid());
    accessCache.forgetAll();
    return ApiResponse.ok(ran, "Lifecycle run");
  }

  // ------------------------------------------------------------------ tenant

  /** For every member: the banner (trial days left, payment due) and the modules on. */
  @GetMapping("/subscription/status")
  @Access(scope = AccessScope.TENANT)
  public ApiResponse<SubscriptionStatusView> status() {
    return ApiResponse.ok(
        subscriptions.status(ExecutionContext.requireTenant()), "Subscription status retrieved");
  }

  @GetMapping("/subscription")
  @Access(scope = AccessScope.TENANT, permission = Permissions.SETTINGS_MANAGE)
  public ApiResponse<SubscriptionView> subscription() {
    return ApiResponse.ok(
        subscriptions.subscription(ExecutionContext.requireTenant()), "Subscription retrieved");
  }

  /** The active plans, to compare with the current one. */
  @GetMapping("/subscription/plans")
  @Access(scope = AccessScope.TENANT, permission = Permissions.SETTINGS_MANAGE)
  public ApiResponse<List<PlanView>> availablePlans() {
    return ApiResponse.ok(
        subscriptions.plans().stream().filter(PlanView::active).toList(), "Plans retrieved");
  }
}
