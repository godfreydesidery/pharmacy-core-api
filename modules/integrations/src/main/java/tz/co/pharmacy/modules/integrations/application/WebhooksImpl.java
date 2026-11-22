package tz.co.pharmacy.modules.integrations.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tz.co.pharmacy.modules.integrations.domain.Webhook;
import tz.co.pharmacy.modules.integrations.domain.WebhookDelivery;
import tz.co.pharmacy.modules.integrations.persistence.WebhookDeliveryRepository;
import tz.co.pharmacy.modules.integrations.persistence.WebhookRepository;
import tz.co.pharmacy.modules.integrations.ports.inbound.DeliveryView;
import tz.co.pharmacy.modules.integrations.ports.inbound.WebhookCommand;
import tz.co.pharmacy.modules.integrations.ports.inbound.WebhookView;
import tz.co.pharmacy.modules.integrations.ports.inbound.Webhooks;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.persistence.Keyset;
import tz.co.pharmacy.platform.persistence.Specs;
import tz.co.pharmacy.platform.persistence.TenantContext;

/**
 * Webhook administration and dispatch. Dispatch keeps the network out of every transaction: one
 * short transaction records the pending deliveries, each delivery is sent outside any transaction,
 * and one short transaction per delivery records its outcome.
 */
@Service
class WebhooksImpl implements Webhooks {

  static final List<String> EVENT_TYPES =
      List.of(
          "sale.completed",
          "sale.returned",
          "goods.received",
          "stock.adjusted",
          "stock.low",
          "product.changed",
          "tenant.registered");

  private static final SecureRandom RANDOM = new SecureRandom();

  private final WebhookRepository webhooks;
  private final WebhookDeliveryRepository deliveries;
  private final WebhookSender sender;
  private final WebhookTargets targets;
  private final TransactionTemplate transactions;
  private final JsonMapper json = JsonMapper.builder().build();

  private final Clock clock;

  WebhooksImpl(
      WebhookRepository webhooks,
      WebhookDeliveryRepository deliveries,
      WebhookSender sender,
      WebhookTargets targets,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.clock = clock;
    this.webhooks = webhooks;
    this.deliveries = deliveries;
    this.sender = sender;
    this.targets = targets;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  @Override
  public List<String> eventTypes() {
    return EVENT_TYPES;
  }

  @Override
  @Transactional(readOnly = true)
  public List<WebhookView> list() {
    return webhooks.findAllByOrderByNameAsc().stream()
        .map(webhook -> toView(webhook, null))
        .toList();
  }

  @Override
  @Transactional
  public WebhookView create(WebhookCommand command) {
    String secret = "whsec_" + HexFormat.of().formatHex(randomBytes(32));
    Webhook webhook =
        webhooks.save(
            new Webhook(
                Checks.text(command.name(), "name", 120),
                url(command.url()),
                secret,
                eventTypes(command.eventTypes()),
                command.active() == null || command.active()));
    return toView(webhook, secret);
  }

  @Override
  @Transactional
  public WebhookView change(UUID webhookUid, WebhookCommand command) {
    Webhook webhook = find(webhookUid);
    webhook.change(
        Checks.text(command.name(), "name", 120),
        url(command.url()),
        eventTypes(command.eventTypes()),
        command.active() == null || command.active());
    return toView(webhook, null);
  }

  @Override
  @Transactional
  public void delete(UUID webhookUid) {
    webhooks.delete(find(webhookUid));
  }

  @Override
  @Transactional(readOnly = true)
  public Page<DeliveryView> deliveries(UUID webhookUid, PageQuery query) {
    find(webhookUid);
    return Keyset.page(
        deliveries, Specs.equal("webhookUid", webhookUid), query, WebhooksImpl::toDeliveryView);
  }

  @Override
  public void dispatch(UUID eventId, String eventType, Instant occurredAt, Object data) {
    UUID tenantUid = TenantContext.requireTenant();
    String body = cloudEvent(eventId, eventType, occurredAt, tenantUid, data);
    record Target(UUID deliveryUid, String url, String secret) {}
    List<Target> targets =
        transactions.execute(
            status -> {
              List<Target> pending = new ArrayList<>();
              for (Webhook webhook : webhooks.findAllByActiveTrue()) {
                if (!webhook.subscribesTo(eventType)
                    || deliveries.existsByWebhookUidAndEventId(webhook.getUid(), eventId)) {
                  continue;
                }
                WebhookDelivery delivery =
                    deliveries.save(
                        new WebhookDelivery(webhook.getUid(), eventType, eventId, body));
                pending.add(new Target(delivery.getUid(), webhook.getUrl(), webhook.getSecret()));
              }
              return pending;
            });
    if (targets == null) {
      return;
    }
    for (Target target : targets) {
      WebhookSender.Outcome outcome = sender.send(target.url(), target.secret(), eventType, body);
      transactions.executeWithoutResult(
          status ->
              deliveries
                  .findByUid(target.deliveryUid())
                  .ifPresent(
                      delivery ->
                          delivery.recordAttempt(
                              outcome.attempts(),
                              outcome.responseStatus(),
                              outcome.error(),
                              outcome.delivered())));
    }
  }

  @Override
  public int retryDue() {
    record Target(
        UUID deliveryUid,
        UUID webhookUid,
        String url,
        String secret,
        String eventType,
        String body) {}
    List<Target> due =
        transactions.execute(
            status -> {
              List<Target> targets = new ArrayList<>();
              for (WebhookDelivery delivery :
                  deliveries.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAt(
                      tz.co.pharmacy.modules.integrations.ports.inbound.DeliveryStatus.FAILED,
                      clock.instant())) {
                var webhook =
                    webhooks.findByUid(delivery.getWebhookUid()).filter(w -> w.isActive());
                if (webhook.isEmpty()) {
                  delivery.giveUp("The webhook was removed or switched off.");
                  continue;
                }
                targets.add(
                    new Target(
                        delivery.getUid(),
                        delivery.getWebhookUid(),
                        webhook.get().getUrl(),
                        webhook.get().getSecret(),
                        delivery.getEventType(),
                        delivery.getPayload()));
              }
              return targets;
            });
    if (due == null) {
      return 0;
    }
    // One lane per webhook, side by side: a system that hangs only delays its own deliveries (the
    // chaos run found one hung system holding back a healthy one for minutes). Within a lane, a
    // delivery that still fails ends the lane for this run: the rest stay due, so a system that is
    // down gets one probe a minute rather than every delivery timing out in turn.
    java.util.Map<UUID, List<Target>> lanes = new java.util.LinkedHashMap<>();
    for (Target target : due) {
      lanes.computeIfAbsent(target.webhookUid(), ignored -> new ArrayList<>()).add(target);
    }
    UUID tenantUid = TenantContext.requireTenant();
    var frame = tz.co.pharmacy.platform.kernel.ExecutionContext.current().orElse(null);
    java.util.concurrent.atomic.AtomicInteger tried =
        new java.util.concurrent.atomic.AtomicInteger();
    try (var lanesRunning = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      for (List<Target> lane : lanes.values()) {
        lanesRunning.submit(
            () -> {
              java.util.function.Supplier<Void> work =
                  () ->
                      TenantContext.callAs(
                          tenantUid,
                          () -> {
                            for (Target target : lane) {
                              tried.incrementAndGet();
                              WebhookSender.Outcome outcome =
                                  sender.send(
                                      target.url(),
                                      target.secret(),
                                      target.eventType(),
                                      target.body());
                              transactions.executeWithoutResult(
                                  status ->
                                      deliveries
                                          .findByUid(target.deliveryUid())
                                          .ifPresent(
                                              delivery ->
                                                  delivery.recordAttempt(
                                                      outcome.attempts(),
                                                      outcome.responseStatus(),
                                                      outcome.error(),
                                                      outcome.delivered())));
                              if (!outcome.delivered()) {
                                break;
                              }
                            }
                            return null;
                          });
              return frame == null
                  ? work.get()
                  : tz.co.pharmacy.platform.kernel.ExecutionContext.callAs(frame, work);
            });
      }
    }
    return tried.get();
  }

  /** A CloudEvents 1.0 JSON envelope around the event, without its internal metadata. */
  String cloudEvent(
      UUID eventId, String eventType, Instant occurredAt, UUID tenantUid, Object data) {
    ObjectNode event = json.createObjectNode();
    event.put("specversion", "1.0");
    event.put("id", eventId.toString());
    event.put("source", "pharmacy-core/tenants/" + tenantUid);
    event.put("type", eventType);
    event.put("time", (occurredAt == null ? clock.instant() : occurredAt).toString());
    event.put("datacontenttype", "application/json");
    JsonNode payload = json.valueToTree(data);
    if (payload instanceof ObjectNode object) {
      object.remove("metadata");
    }
    event.set("data", payload);
    return json.writeValueAsString(event);
  }

  private Webhook find(UUID webhookUid) {
    return webhooks
        .findByUid(webhookUid)
        .orElseThrow(() -> Refusal.notFound("webhook_not_found", "No such webhook."));
  }

  /** An https URL to a public host; see {@link WebhookTargets}. */
  private String url(String value) {
    String url = Checks.text(value, "url", 2000).trim();
    WebhookTargets.Verdict verdict = targets.check(url);
    if (!verdict.allowed()) {
      throw Refusal.invalid("webhook_url_not_allowed", verdict.problem(), "url");
    }
    return url;
  }

  private List<String> eventTypes(List<String> values) {
    Checks.notEmpty(values, "eventTypes");
    LinkedHashSet<String> types = new LinkedHashSet<>();
    for (String value : values) {
      String type = value == null ? "" : value.trim().toLowerCase();
      if (!EVENT_TYPES.contains(type)) {
        throw Refusal.invalid(
            "invalid_event_types", "eventTypes holds an unknown type: " + value, "eventTypes");
      }
      types.add(type);
    }
    return List.copyOf(types);
  }

  private static byte[] randomBytes(int count) {
    byte[] bytes = new byte[count];
    RANDOM.nextBytes(bytes);
    return bytes;
  }

  private static WebhookView toView(Webhook webhook, String secret) {
    return new WebhookView(
        webhook.getUid(),
        webhook.getName(),
        webhook.getUrl(),
        webhook.eventTypeList(),
        webhook.isActive(),
        secret,
        webhook.getCreatedAt());
  }

  private static DeliveryView toDeliveryView(WebhookDelivery delivery) {
    return new DeliveryView(
        delivery.getUid(),
        delivery.getEventType(),
        delivery.getEventId(),
        delivery.getStatus(),
        delivery.getResponseStatus(),
        delivery.getAttempts(),
        delivery.getLastError(),
        delivery.getCreatedAt(),
        delivery.getDeliveredAt(),
        delivery.getNextAttemptAt());
  }
}
