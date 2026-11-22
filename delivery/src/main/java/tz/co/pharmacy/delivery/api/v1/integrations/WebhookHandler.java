package tz.co.pharmacy.delivery.api.v1.integrations;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.delivery.api.Pages;
import tz.co.pharmacy.delivery.api.RequiresFeature;
import tz.co.pharmacy.modules.integrations.ports.inbound.DeliveryView;
import tz.co.pharmacy.modules.integrations.ports.inbound.WebhookCommand;
import tz.co.pharmacy.modules.integrations.ports.inbound.WebhookView;
import tz.co.pharmacy.modules.integrations.ports.inbound.Webhooks;
import tz.co.pharmacy.modules.tenancy.ports.inbound.PlanFeature;
import tz.co.pharmacy.platform.kernel.Page;

/** Outbound webhooks: external systems that receive the tenant's events as CloudEvents. */
@RestController
@RequestMapping("/api/v1/webhooks")
@RequiresFeature(PlanFeature.INTEGRATIONS)
public class WebhookHandler {

  private final Webhooks webhooks;

  public WebhookHandler(Webhooks webhooks) {
    this.webhooks = webhooks;
  }

  public record WebhookRequest(String name, String url, List<String> eventTypes, Boolean active) {

    WebhookCommand toCommand() {
      return new WebhookCommand(name, url, eventTypes, active);
    }
  }

  @GetMapping
  @Access(scope = AccessScope.TENANT, permission = "integrations.manage")
  public ApiResponse<?> list() {
    return ApiResponse.ok(Pages.slice(webhooks.list()), "Webhooks retrieved");
  }

  @GetMapping("/event-types")
  @Access(scope = AccessScope.TENANT, permission = "integrations.manage")
  public ApiResponse<List<String>> eventTypes() {
    return ApiResponse.ok(webhooks.eventTypes(), "Event types retrieved");
  }

  /** The response carries the webhook's signing secret: the only time it is shown. */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.TENANT, permission = "integrations.manage")
  public ApiResponse<WebhookView> create(@RequestBody WebhookRequest body) {
    return ApiResponse.created(webhooks.create(body.toCommand()), "Webhook created");
  }

  @PutMapping("/{webhookUid}")
  @Access(scope = AccessScope.TENANT, permission = "integrations.manage")
  public ApiResponse<WebhookView> change(
      @PathVariable UUID webhookUid, @RequestBody WebhookRequest body) {
    return ApiResponse.ok(webhooks.change(webhookUid, body.toCommand()), "Webhook changed");
  }

  @DeleteMapping("/{webhookUid}")
  @Access(scope = AccessScope.TENANT, permission = "integrations.manage")
  public ApiResponse<Void> delete(@PathVariable UUID webhookUid) {
    webhooks.delete(webhookUid);
    return ApiResponse.ok("Webhook deleted");
  }

  @GetMapping("/{webhookUid}/deliveries")
  @Access(scope = AccessScope.TENANT, permission = "integrations.manage")
  public ApiResponse<Page<DeliveryView>> deliveries(
      @PathVariable UUID webhookUid,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    return ApiResponse.ok(
        webhooks.deliveries(webhookUid, Pages.of(cursor, limit)), "Deliveries retrieved");
  }
}
