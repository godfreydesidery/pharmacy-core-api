package tz.co.pharmacy.delivery.api.v1.notifications;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import tz.co.pharmacy.delivery.api.Pages;
import tz.co.pharmacy.modules.notifications.ports.inbound.Channel;
import tz.co.pharmacy.modules.notifications.ports.inbound.DeliveryLog;
import tz.co.pharmacy.modules.notifications.ports.inbound.DeliveryView;
import tz.co.pharmacy.modules.notifications.ports.inbound.PreferenceView;
import tz.co.pharmacy.modules.notifications.ports.inbound.Preferences;
import tz.co.pharmacy.modules.notifications.ports.inbound.RenderedPreview;
import tz.co.pharmacy.modules.notifications.ports.inbound.TemplateCommand;
import tz.co.pharmacy.modules.notifications.ports.inbound.TemplateView;
import tz.co.pharmacy.modules.notifications.ports.inbound.Templates;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * Notification preferences (each user's own), and the tenant's templates and delivery log (for
 * whoever manages settings).
 */
@RestController
public class NotificationAdminHandler {

  private final Preferences preferences;
  private final Templates templates;
  private final DeliveryLog deliveries;

  public NotificationAdminHandler(
      Preferences preferences, Templates templates, DeliveryLog deliveries) {
    this.preferences = preferences;
    this.templates = templates;
    this.deliveries = deliveries;
  }

  public record PreferenceRequest(String category, List<String> channels) {}

  public record TemplateRequest(String subject, String body) {}

  public record PreviewRequest(
      String key, String channel, String locale, String subject, String body) {}

  @GetMapping("/api/v1/notification-preferences")
  @Access(scope = AccessScope.TENANT)
  public ApiResponse<List<PreferenceView>> preferences() {
    return ApiResponse.ok(preferences.mine(), "Preferences retrieved");
  }

  @PutMapping("/api/v1/notification-preferences")
  @Access(scope = AccessScope.TENANT)
  public ApiResponse<List<PreferenceView>> changePreferences(
      @RequestBody List<PreferenceRequest> body) {
    if (body == null) {
      throw Refusal.malformed("Send a list of preferences.");
    }
    List<PreferenceView> changes =
        body.stream()
            .map(
                change -> {
                  Set<Channel> channels = EnumSet.noneOf(Channel.class);
                  for (String channel :
                      change.channels() == null ? List.<String>of() : change.channels()) {
                    channels.add(Checks.oneOf(Channel.class, channel, "channels"));
                  }
                  return new PreferenceView(change.category(), channels);
                })
            .toList();
    return ApiResponse.ok(preferences.change(changes), "Preferences changed");
  }

  @GetMapping("/api/v1/notification-templates")
  @Access(scope = AccessScope.TENANT, permission = "settings.manage")
  public ApiResponse<List<TemplateView>> templates() {
    return ApiResponse.ok(templates.list(), "Templates retrieved");
  }

  @PutMapping("/api/v1/notification-templates/{key}/{channel}/{locale}")
  @Access(scope = AccessScope.TENANT, permission = "settings.manage")
  public ApiResponse<TemplateView> override(
      @PathVariable String key,
      @PathVariable String channel,
      @PathVariable String locale,
      @RequestBody TemplateRequest body) {
    return ApiResponse.ok(
        templates.override(
            key,
            Checks.oneOf(Channel.class, channel, "channel"),
            locale,
            new TemplateCommand(body.subject(), body.body())),
        "Template saved");
  }

  @DeleteMapping("/api/v1/notification-templates/{key}/{channel}/{locale}")
  @Access(scope = AccessScope.TENANT, permission = "settings.manage")
  public ApiResponse<Void> reset(
      @PathVariable String key, @PathVariable String channel, @PathVariable String locale) {
    templates.reset(key, Checks.oneOf(Channel.class, channel, "channel"), locale);
    return ApiResponse.ok("Template reset to the default");
  }

  @PostMapping("/api/v1/notification-templates/preview")
  @Access(scope = AccessScope.TENANT, permission = "settings.manage")
  public ApiResponse<RenderedPreview> preview(@RequestBody PreviewRequest body) {
    TemplateCommand draft =
        body.body() == null ? null : new TemplateCommand(body.subject(), body.body());
    return ApiResponse.ok(
        templates.preview(
            body.key(),
            Checks.oneOf(Channel.class, body.channel(), "channel"),
            body.locale(),
            draft),
        "Preview rendered");
  }

  @GetMapping("/api/v1/notification-deliveries")
  @Access(scope = AccessScope.TENANT, permission = "settings.manage")
  public ApiResponse<Page<DeliveryView>> deliveries(
      @RequestParam(required = false) String channel,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    Channel wanted =
        channel == null || channel.isBlank()
            ? null
            : Checks.oneOf(Channel.class, channel, "channel");
    return ApiResponse.ok(
        deliveries.list(wanted, status, Pages.of(cursor, limit)), "Deliveries retrieved");
  }
}
