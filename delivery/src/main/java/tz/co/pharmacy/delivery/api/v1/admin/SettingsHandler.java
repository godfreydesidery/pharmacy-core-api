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

  public SettingsHandler(SettingsService settings, Entitlements entitlements) {
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
  public ApiResponse<SettingsView> change(@RequestBody SettingsRequest body) {
    if (body == null) {
      throw Refusal.malformed("Settings are required.");
    }
    requireMultiCurrencyFor(body.acceptedCurrencies());
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
                body.acceptedCurrencies())),
        "Settings changed");
  }

  /** Accepting a currency besides the base one needs the plan's multi-currency module. */
  private void requireMultiCurrencyFor(java.util.List<String> accepted) {
    if (accepted == null || ExecutionContext.current().map(f -> f.rootAdmin()).orElse(false)) {
      return;
    }
    String base = settings.current().currency();
    boolean foreign =
        accepted.stream()
            .filter(c -> c != null && !c.isBlank())
            .anyMatch(c -> !c.trim().equalsIgnoreCase(base));
    if (foreign) {
      entitlements.requireFeature(ExecutionContext.requireTenant(), PlanFeature.MULTI_CURRENCY);
    }
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
      java.util.List<String> acceptedCurrencies) {}
}
