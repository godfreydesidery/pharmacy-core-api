package tz.co.pharmacy.delivery.api.v1.notifications;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.modules.notifications.ports.inbound.Channel;
import tz.co.pharmacy.modules.notifications.ports.inbound.NotificationUsage;
import tz.co.pharmacy.modules.notifications.ports.inbound.ProviderSettings;
import tz.co.pharmacy.modules.notifications.ports.inbound.ProviderSettings.ProviderSettingCommand;
import tz.co.pharmacy.modules.notifications.ports.inbound.ProviderSettings.ProviderSettingView;
import tz.co.pharmacy.modules.notifications.ports.inbound.ProviderSettings.TestResult;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * Message providers: the platform's defaults (root administrators) and a pharmacy's own accounts
 * (settings.manage), each with a test send; and the pharmacy's monthly message usage.
 */
@RestController
@RequiredArgsConstructor
public class NotificationProvidersHandler {

  private final ProviderSettings providers;
  private final NotificationUsage usage;

  public record TestRequest(String to) {}

  @GetMapping("/api/v1/platform/notification-providers")
  @Access(scope = AccessScope.PLATFORM, permission = "platform.manage")
  public ApiResponse<List<ProviderSettingView>> platform() {
    return ApiResponse.ok(providers.platform(), "Platform providers retrieved");
  }

  @PutMapping("/api/v1/platform/notification-providers/{channel}")
  @Access(scope = AccessScope.PLATFORM, permission = "platform.manage")
  public ApiResponse<ProviderSettingView> savePlatform(
      @PathVariable String channel, @RequestBody ProviderSettingCommand body) {
    return ApiResponse.ok(
        providers.savePlatform(channel(channel), body), "Platform provider saved");
  }

  @PostMapping("/api/v1/platform/notification-providers/{channel}/test")
  @Access(scope = AccessScope.PLATFORM, permission = "platform.manage")
  public ApiResponse<TestResult> testPlatform(
      @PathVariable String channel, @RequestBody TestRequest body) {
    return ApiResponse.ok(
        providers.testPlatform(channel(channel), body == null ? null : body.to()), "Test sent");
  }

  @GetMapping("/api/v1/notification-providers")
  @Access(scope = AccessScope.TENANT, permission = "settings.manage")
  public ApiResponse<List<ProviderSettingView>> tenant() {
    return ApiResponse.ok(providers.tenant(), "Providers retrieved");
  }

  @PutMapping("/api/v1/notification-providers/{channel}")
  @Access(scope = AccessScope.TENANT, permission = "settings.manage")
  public ApiResponse<ProviderSettingView> saveTenant(
      @PathVariable String channel, @RequestBody ProviderSettingCommand body) {
    return ApiResponse.ok(providers.saveTenant(channel(channel), body), "Provider saved");
  }

  @PostMapping("/api/v1/notification-providers/{channel}/test")
  @Access(scope = AccessScope.TENANT, permission = "settings.manage")
  public ApiResponse<TestResult> testTenant(
      @PathVariable String channel, @RequestBody TestRequest body) {
    return ApiResponse.ok(
        providers.testTenant(channel(channel), body == null ? null : body.to()), "Test sent");
  }

  /** Messages the pharmacy sent per channel in the month ({@code YYYY-MM}, default this one). */
  @GetMapping("/api/v1/notification-usage")
  @Access(scope = AccessScope.TENANT, permission = "settings.manage")
  public ApiResponse<Map<Channel, Long>> usage(@RequestParam(required = false) String month) {
    YearMonth m;
    try {
      m = month == null || month.isBlank() ? null : YearMonth.parse(month.trim());
    } catch (DateTimeParseException bad) {
      throw Refusal.invalid("invalid_month", "month must look like 2026-11.", "month");
    }
    return ApiResponse.ok(
        usage.usage(
            ExecutionContext.tenantUid()
                .orElseThrow(() -> Refusal.forbidden("tenant_required", "Choose a pharmacy.")),
            m),
        "Usage retrieved");
  }

  private static Channel channel(String value) {
    return Checks.oneOf(Channel.class, value, "channel");
  }
}
