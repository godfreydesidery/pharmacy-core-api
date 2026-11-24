package tz.co.pharmacy.delivery.api.v1.admin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.modules.access.ports.inbound.Permissions;
import tz.co.pharmacy.modules.tenancy.ports.inbound.ChangeSettingsCommand;
import tz.co.pharmacy.modules.tenancy.ports.inbound.Entitlements;
import tz.co.pharmacy.modules.tenancy.ports.inbound.PlanFeature;
import tz.co.pharmacy.modules.tenancy.ports.inbound.SettingsService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.SettingsView;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Refusal;

@RestController
@RequestMapping("/api/v1/settings")
public class SettingsHandler {

  private final SettingsService settings;
  private final Entitlements entitlements;
  private final tz.co.pharmacy.delivery.edge.TenantPolicies policies;

  public SettingsHandler(
      SettingsService settings,
      Entitlements entitlements,
      tz.co.pharmacy.delivery.edge.TenantPolicies policies) {
    this.policies = policies;
    this.settings = settings;
    this.entitlements = entitlements;
  }

  /** The tenant's settings, for any member: currency and receipt texts are needed everywhere. */
  @GetMapping
  @Access(scope = AccessScope.TENANT)
  public ApiResponse<SettingsView> current() {
    return ApiResponse.ok(settings.current(), "Settings retrieved");
  }

  /** {@code currency} and {@code timezone} are fixed at registration and ignored here. */
  @PutMapping
  @Access(scope = AccessScope.TENANT, permission = Permissions.SETTINGS_MANAGE)
  public ApiResponse<SettingsView> change(
      @RequestBody SettingsRequest body, jakarta.servlet.http.HttpServletRequest request) {
    if (body == null) {
      throw Refusal.malformed("Settings are required.");
    }
    if (Boolean.TRUE.equals(body.requireTwoFactor())
        && request.getAttribute(tz.co.pharmacy.delivery.edge.EdgeFilter.PRINCIPAL)
            instanceof tz.co.pharmacy.modules.access.ports.inbound.Principal principal
        && !principal.rootAdmin()
        && !principal.secondFactor()) {
      // Turning it on without one would lock the admin out of these very settings.
      throw Refusal.invalid(
          "two_factor_needed_first",
          "Set up two-factor on your own account before requiring it of everyone.",
          "requireTwoFactor");
    }
    requireMultiCurrencyFor(body.acceptedCurrencies());
    ExecutionContext.tenantUid().ifPresent(policies::forget);
    return ApiResponse.ok(
        settings.change(
            new ChangeSettingsCommand(
                body.defaultTaxRatePercent(),
                body.invoicePrefix(),
                body.receiptFooter(),
                body.maxDiscountPercent(),
                body.expiryAlertDays(),
                body.lowStockAlerts(),
                body.smsReceipts(),
                body.acceptedCurrencies(),
                body.pricesIncludeTax(),
                body.look(),
                body.idleMinutes(),
                body.requireTwoFactor(),
                body.approvalLimit(),
                body.returnWindowDays(),
                body.requireOpenShift(),
                body.vatRegistered(),
                body.tin(),
                body.vrn())),
        "Settings changed");
  }

  /**
   * Adding a currency besides the base one needs the plan's multi-currency module. Keeping or
   * removing currencies already accepted does not, so after a downgrade the other settings can
   * still be saved; the till refuses foreign tenders on such a plan anyway.
   */
  private void requireMultiCurrencyFor(java.util.List<String> accepted) {
    if (accepted == null || ExecutionContext.current().map(f -> f.rootAdmin()).orElse(false)) {
      return;
    }
    var current = settings.current();
    String base = current.currency();
    java.util.Set<String> requested = foreign(accepted, base);
    if (requested.isEmpty() || foreign(current.acceptedCurrencies(), base).containsAll(requested)) {
      return;
    }
    try {
      entitlements.requireFeature(ExecutionContext.requireTenant(), PlanFeature.MULTI_CURRENCY);
    } catch (Refusal refusal) {
      throw Refusal.forbidden(
              refusal.reason(),
              "The subscription plan does not include more than one currency, so only "
                  + base
                  + " can be accepted. Remove "
                  + String.join(", ", requested)
                  + " from the accepted currencies, or upgrade the plan.")
          .about("acceptedCurrencies");
    }
  }

  private static java.util.Set<String> foreign(java.util.List<String> currencies, String base) {
    java.util.Set<String> codes = new java.util.TreeSet<>();
    if (currencies != null) {
      currencies.stream()
          .filter(c -> c != null && !c.isBlank())
          .map(c -> c.trim().toUpperCase(java.util.Locale.ROOT))
          .filter(c -> !c.equalsIgnoreCase(base))
          .forEach(codes::add);
    }
    return codes;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SettingsRequest(
      BigDecimal defaultTaxRatePercent,
      String invoicePrefix,
      String receiptFooter,
      BigDecimal maxDiscountPercent,
      Integer expiryAlertDays,
      Boolean lowStockAlerts,
      Boolean smsReceipts,
      java.util.List<String> acceptedCurrencies,
      Boolean pricesIncludeTax,
      String look,
      Integer idleMinutes,
      Boolean requireTwoFactor,
      java.math.BigDecimal approvalLimit,
      Integer returnWindowDays,
      Boolean requireOpenShift,
      Boolean vatRegistered,
      String tin,
      String vrn) {}
}
