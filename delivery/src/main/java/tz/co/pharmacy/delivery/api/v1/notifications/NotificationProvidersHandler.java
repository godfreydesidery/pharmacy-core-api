package tz.co.pharmacy.delivery.api.v1.notifications;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.modules.notifications.ports.inbound.Channel;
import tz.co.pharmacy.modules.notifications.ports.inbound.NotificationUsage;
import tz.co.pharmacy.modules.notifications.ports.inbound.ProviderSettings;
import tz.co.pharmacy.modules.notifications.ports.inbound.ProviderSettings.ChannelView;
import tz.co.pharmacy.modules.notifications.ports.inbound.ProviderSettings.ProviderCommand;
import tz.co.pharmacy.modules.notifications.ports.inbound.ProviderSettings.ProviderView;
import tz.co.pharmacy.modules.notifications.ports.inbound.ProviderSettings.TestResult;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * Message providers: the platform's lists (root administrators) and a pharmacy's own
 * (settings.manage). Each channel holds several providers tried in order (failover); each can be
 * added, changed, removed, reordered and tested on its own, or the channel tested as messages go.
 * And the pharmacy's monthly message usage.
 */
@RestController
@RequiredArgsConstructor
public class NotificationProvidersHandler {

  private static final String PLATFORM = "/api/v1/platform/notification-providers";
  private static final String TENANT = "/api/v1/notification-providers";

  private final ProviderSettings providers;
  private final NotificationUsage usage;

  /** {@code providerUid}: test that one provider; empty, the channel's list as messages go. */
  public record TestRequest(String to, UUID providerUid) {}

  public record OrderRequest(List<UUID> order) {}

  // platform

  @GetMapping(PLATFORM)
  @Access(scope = AccessScope.PLATFORM, permission = "platform.manage")
  public ApiResponse<List<ChannelView>> platform() {
    return ApiResponse.ok(providers.platform(), "Platform providers retrieved");
  }

  @PostMapping(PLATFORM + "/{channel}")
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.PLATFORM, permission = "platform.manage")
  public ApiResponse<ProviderView> addPlatform(
      @PathVariable String channel, @RequestBody ProviderCommand body) {
    return ApiResponse.created(providers.addPlatform(channel(channel), body), "Provider added");
  }

  @PutMapping(PLATFORM + "/{channel}/{providerUid}")
  @Access(scope = AccessScope.PLATFORM, permission = "platform.manage")
  public ApiResponse<ProviderView> changePlatform(
      @PathVariable String channel,
      @PathVariable UUID providerUid,
      @RequestBody ProviderCommand body) {
    return ApiResponse.ok(providers.changePlatform(providerUid, body), "Provider saved");
  }

  @DeleteMapping(PLATFORM + "/{channel}/{providerUid}")
  @Access(scope = AccessScope.PLATFORM, permission = "platform.manage")
  public ApiResponse<Void> removePlatform(
      @PathVariable String channel, @PathVariable UUID providerUid) {
    providers.removePlatform(providerUid);
    return ApiResponse.ok(null, "Provider removed");
  }

  @PutMapping(PLATFORM + "/{channel}/order")
  @Access(scope = AccessScope.PLATFORM, permission = "platform.manage")
  public ApiResponse<ChannelView> reorderPlatform(
      @PathVariable String channel, @RequestBody OrderRequest body) {
    return ApiResponse.ok(
        providers.reorderPlatform(channel(channel), body == null ? null : body.order()),
        "Order saved");
  }

  @PostMapping(PLATFORM + "/{channel}/test")
  @Access(scope = AccessScope.PLATFORM, permission = "platform.manage")
  public ApiResponse<TestResult> testPlatform(
      @PathVariable String channel, @RequestBody TestRequest body) {
    return ApiResponse.ok(
        providers.testPlatform(
            channel(channel),
            body == null ? null : body.providerUid(),
            body == null ? null : body.to()),
        "Test sent");
  }

  // the pharmacy's own

  @GetMapping(TENANT)
  @Access(scope = AccessScope.TENANT, permission = "settings.manage")
  public ApiResponse<List<ChannelView>> tenant() {
    return ApiResponse.ok(providers.tenant(), "Providers retrieved");
  }

  @PostMapping(TENANT + "/{channel}")
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.TENANT, permission = "settings.manage")
  public ApiResponse<ProviderView> addTenant(
      @PathVariable String channel, @RequestBody ProviderCommand body) {
    return ApiResponse.created(providers.addTenant(channel(channel), body), "Provider added");
  }

  @PutMapping(TENANT + "/{channel}/{providerUid}")
  @Access(scope = AccessScope.TENANT, permission = "settings.manage")
  public ApiResponse<ProviderView> changeTenant(
      @PathVariable String channel,
      @PathVariable UUID providerUid,
      @RequestBody ProviderCommand body) {
    return ApiResponse.ok(providers.changeTenant(providerUid, body), "Provider saved");
  }

  @DeleteMapping(TENANT + "/{channel}/{providerUid}")
  @Access(scope = AccessScope.TENANT, permission = "settings.manage")
  public ApiResponse<Void> removeTenant(
      @PathVariable String channel, @PathVariable UUID providerUid) {
    providers.removeTenant(providerUid);
    return ApiResponse.ok(null, "Provider removed");
  }

  @PutMapping(TENANT + "/{channel}/order")
  @Access(scope = AccessScope.TENANT, permission = "settings.manage")
  public ApiResponse<ChannelView> reorderTenant(
      @PathVariable String channel, @RequestBody OrderRequest body) {
    return ApiResponse.ok(
        providers.reorderTenant(channel(channel), body == null ? null : body.order()),
        "Order saved");
  }

  @PostMapping(TENANT + "/{channel}/test")
  @Access(scope = AccessScope.TENANT, permission = "settings.manage")
  public ApiResponse<TestResult> testTenant(
      @PathVariable String channel, @RequestBody TestRequest body) {
    return ApiResponse.ok(
        providers.testTenant(
            channel(channel),
            body == null ? null : body.providerUid(),
            body == null ? null : body.to()),
        "Test sent");
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
