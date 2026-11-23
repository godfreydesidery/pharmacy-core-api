package tz.co.pharmacy.platform.composition;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.notifications.ports.outbound.SmsAllowance;
import tz.co.pharmacy.modules.tenancy.ports.inbound.Subscriptions;
import tz.co.pharmacy.modules.tenancy.ports.inbound.Subscriptions.PlanView;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantView;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * Binds the SMS cap of notifications to tenancy: the plan's {@code sms_per_month} (a pharmacy with
 * no subscription row is on the trial plan, as tenancy treats it) and the pharmacy's time zone.
 * Tenancy is reached lazily: its subscriptions send notices through notifications.
 */
@Component
class NotificationsTenancyAdapter implements SmsAllowance {

  private final ObjectProvider<Subscriptions> subscriptions;
  private final ObjectProvider<TenantService> tenants;
  private final String trialPlan;

  NotificationsTenancyAdapter(
      ObjectProvider<Subscriptions> subscriptions,
      ObjectProvider<TenantService> tenants,
      @Value("${pharmacy.subscriptions.trial-plan:PRO}") String trialPlan) {
    this.subscriptions = subscriptions;
    this.tenants = tenants;
    this.trialPlan = trialPlan;
  }

  @Override
  public Allowance of(UUID tenantUid) {
    Subscriptions plans = subscriptions.getObject();
    String code;
    try {
      code = plans.status(tenantUid).planCode();
    } catch (Refusal noSubscription) {
      code = trialPlan;
    }
    String planCode = code;
    PlanView plan =
        plans.plans().stream().filter(p -> p.code().equals(planCode)).findFirst().orElse(null);
    return new Allowance(
        plan == null ? null : plan.smsPerMonth(),
        plan == null ? null : plan.name(),
        zone(tenantUid));
  }

  private ZoneId zone(UUID tenantUid) {
    String timezone = tenants.getObject().find(tenantUid).map(TenantView::timezone).orElse(null);
    try {
      return timezone == null || timezone.isBlank() ? ZoneOffset.UTC : ZoneId.of(timezone);
    } catch (DateTimeException unknown) {
      return ZoneOffset.UTC;
    }
  }
}
