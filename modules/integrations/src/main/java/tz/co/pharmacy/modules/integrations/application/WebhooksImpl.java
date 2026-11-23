package tz.co.pharmacy.modules.integrations.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.persistence.Keyset;
import tz.co.pharmacy.platform.persistence.Specs;
import tz.co.pharmacy.platform.persistence.TenantContext;

/**
 * Webhook administration and dispatch. Dispatch keeps the network out of every transaction: one
 * short transaction records the pending deliveries, each delivery is sent outside any transaction
 * (different webhooks side by side, on virtual threads), and one short transaction per delivery
 * records its outcome.
 */
@Service
class WebhooksImpl implements Webhooks {

  private static final Logger log = LoggerFactory.getLogger(WebhooksImpl.class);

  static final List<String> EVENT_TYPES =
      List.of(
          "sale.completed",
          "sale.returned",
          "goods.received",
          "stock.adjusted",
          "stock.low",
          "product.changed",
          "tenant.registered");

  /** How long the secret before a rotation keeps signing. */
  static final Duration ROTATION_OVERLAP = Duration.ofHours(24);

  static final String UNREADABLE_SECRET =
      "The webhook's signing secret can no longer be read (the server's encryption key changed)."
          + " Rotate the secret and give the new one to the receiving system.";

  private final WebhookRepository webhooks;
  private final WebhookDeliveryRepository deliveries;
  private final WebhookSender sender;
  private final WebhookTargets targets;
  private final WebhookSecrets secrets;
  private final TransactionTemplate transactions;
  private final JsonMapper json = JsonMapper.builder().build();
  private final Clock clock;
  private final Duration claimLease;

  @SuppressWarnings("java:S107")
  WebhooksImpl(
      WebhookRepository webhooks,
      WebhookDeliveryRepository deliveries,
      WebhookSender sender,
      WebhookTargets targets,
      WebhookSecrets secrets,
      PlatformTransactionManager transactionManager,
      Clock clock,
      @Value("${pharmacy.integrations.webhooks.claim-lease:PT10M}") Duration claimLease) {
    this.clock = clock;
    this.webhooks = webhooks;
    this.deliveries = deliveries;
    this.sender = sender;
    this.targets = targets;
    this.secrets = secrets;
    this.claimLease = claimLease;
    this.transactions =
        transactionManager == null ? null : new TransactionTemplate(transactionManager);
  }

  @Override
  public List<String> eventTypes() {
    return EVENT_TYPES;
  }

  @Override
  @Transactional(readOnly = true)
  public List<WebhookView> list() {
    Instant now = clock.instant();
    return webhooks.findAllByOrderByNameAsc().stream()
        .map(webhook -> toView(webhook, null, now))
        .toList();
  }

  @Override
  @Transactional
  public WebhookView create(WebhookCommand command) {
    String name = Checks.text(command.name(), "name", 120);
    String url = url(command.url());
    List<String> types = eventTypes(command.eventTypes());
    // Counted under a per-pharmacy lock, so two instances creating at once cannot pass the cap.
    webhooks.lock("integrations.webhooks:" + TenantContext.requireTenant());
    if (webhooks.count() >= MAX_WEBHOOKS) {
      throw Refusal.invalid(
          "webhook_limit",
          "A pharmacy may have at most " + MAX_WEBHOOKS + " webhooks. Remove one to add another.",
          "webhook");
    }
    String secret = WebhookSecrets.generate();
    Webhook webhook =
        webhooks.save(
            new Webhook(
                name,
                url,
                secrets.seal(secret),
                types,
                command.active() == null || command.active()));
    return toView(webhook, secret, clock.instant());
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
    secrets.sealInPlace(webhook);
    return toView(webhook, null, clock.instant());
  }

  @Override
  @Transactional
  public WebhookView rotateSecret(UUID webhookUid) {
    Webhook webhook = find(webhookUid);
    secrets.sealInPlace(webhook);
    Instant now = clock.instant();
    String current = webhook.getSecret();
    boolean readable;
    try {
      secrets.open(current);
      readable = true;
    } catch (IllegalStateException unreadable) {
      // Nobody can verify with a secret the server cannot read: it is not kept.
      readable = false;
    }
    String fresh = WebhookSecrets.generate();
    webhook.rotate(
        secrets.seal(fresh),
        readable ? current : null,
        readable ? now.plus(ROTATION_OVERLAP) : null);
    return toView(webhook, fresh, now);
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

  /**
   * One delivery to send: its signing secrets (null when they cannot be read), the Standard
   * Webhooks message id (the event id), and when it was due (to release an unsent claim).
   */
  private record Target(
      UUID deliveryUid,
      UUID webhookUid,
      String url,
      List<String> secrets,
      String messageId,
      String eventType,
      String body,
      Instant dueAt) {}

  @Override
  public void dispatch(UUID eventId, String eventType, Instant occurredAt, Object data) {
    UUID tenantUid = TenantContext.requireTenant();
    String body = cloudEvent(eventId, eventType, occurredAt, tenantUid, data);
    List<Target> pending =
        transactions.execute(
            status -> {
              Instant now = clock.instant();
              List<Target> out = new ArrayList<>();
              for (Webhook webhook : webhooks.findAllByActiveTrue()) {
                if (!webhook.subscribesTo(eventType)
                    || deliveries.existsByWebhookUidAndEventId(webhook.getUid(), eventId)) {
                  continue;
                }
                secrets.sealInPlace(webhook);
                WebhookDelivery delivery =
                    deliveries.save(
                        new WebhookDelivery(webhook.getUid(), eventType, eventId, body));
                out.add(
                    new Target(
                        delivery.getUid(),
                        webhook.getUid(),
                        webhook.getUrl(),
                        signing(webhook, now),
                        eventId.toString(),
                        eventType,
                        body,
                        null));
              }
              return out;
            });
    if (pending == null || pending.isEmpty()) {
      return;
    }
    // Each webhook on its own lane: a slow receiver delays only its own delivery.
    inLanes(pending.stream().map(List::of).toList(), tenantUid, new AtomicInteger());
  }

  @Override
  public int retryDue() {
    List<Target> due =
        transactions.execute(
            status -> {
              Instant now = clock.instant();
              webhooks.findWithPlainSecrets().forEach(secrets::sealInPlace);
              Map<UUID, Optional<Webhook>> hooks = new HashMap<>();
              List<Target> out = new ArrayList<>();
              for (WebhookDelivery delivery : deliveries.lockDue(now)) {
                Optional<Webhook> webhook =
                    hooks.computeIfAbsent(
                        delivery.getWebhookUid(),
                        uid -> webhooks.findByUid(uid).filter(Webhook::isActive));
                if (webhook.isEmpty()) {
                  delivery.giveUp("The webhook was removed or switched off.");
                  continue;
                }
                Instant dueAt = delivery.getNextAttemptAt();
                delivery.claimUntil(now.plus(claimLease));
                out.add(
                    new Target(
                        delivery.getUid(),
                        delivery.getWebhookUid(),
                        webhook.get().getUrl(),
                        signing(webhook.get(), now),
                        delivery.getEventId().toString(),
                        delivery.getEventType(),
                        delivery.getPayload(),
                        dueAt));
              }
              return out;
            });
    if (due == null || due.isEmpty()) {
      return 0;
    }
    // One lane per webhook, side by side: a system that hangs only delays its own deliveries (the
    // chaos run found one hung system holding back a healthy one for minutes). Within a lane, a
    // delivery that still fails ends the lane for this run: the rest go back to due, so a system
    // that is down gets one probe a minute rather than every delivery timing out in turn.
    Map<UUID, List<Target>> lanes = new LinkedHashMap<>();
    for (Target target : due) {
      lanes.computeIfAbsent(target.webhookUid(), ignored -> new ArrayList<>()).add(target);
    }
    AtomicInteger tried = new AtomicInteger();
    inLanes(lanes.values(), TenantContext.requireTenant(), tried);
    return tried.get();
  }

  /** Runs each lane on its own virtual thread, in the caller's scope, and waits for them all. */
  private void inLanes(Collection<List<Target>> lanes, UUID tenantUid, AtomicInteger tried) {
    ExecutionContext.Frame frame = ExecutionContext.current().orElse(null);
    try (var running = Executors.newVirtualThreadPerTaskExecutor()) {
      for (List<Target> lane : lanes) {
        running.submit(
            () -> {
              Supplier<Void> work =
                  () ->
                      TenantContext.callAs(
                          tenantUid,
                          () -> {
                            runLane(lane, tried);
                            return null;
                          });
              return frame == null ? work.get() : ExecutionContext.callAs(frame, work);
            });
      }
    }
  }

  private void runLane(List<Target> lane, AtomicInteger tried) {
    for (int i = 0; i < lane.size(); i++) {
      Target target = lane.get(i);
      tried.incrementAndGet();
      if (!attempt(target)) {
        release(lane.subList(i + 1, lane.size()));
        return;
      }
    }
  }

  /** Sends one delivery and records the outcome; returns whether it was delivered. */
  private boolean attempt(Target target) {
    WebhookSender.Outcome outcome;
    try {
      outcome =
          target.secrets() == null
              ? new WebhookSender.Outcome(false, 0, null, UNREADABLE_SECRET)
              : sender.send(
                  target.url(),
                  target.secrets(),
                  target.messageId(),
                  target.eventType(),
                  target.body());
    } catch (RuntimeException failure) {
      outcome = new WebhookSender.Outcome(false, 1, null, failure.toString());
    }
    WebhookSender.Outcome result = outcome;
    transactions.executeWithoutResult(
        status ->
            deliveries
                .findByUid(target.deliveryUid())
                .ifPresent(
                    delivery ->
                        delivery.recordAttempt(
                            result.attempts(),
                            result.responseStatus(),
                            result.error(),
                            result.delivered())));
    return result.delivered();
  }

  /** Claimed deliveries this run did not try are due again when they were. */
  private void release(List<Target> untried) {
    if (untried.isEmpty()) {
      return;
    }
    transactions.executeWithoutResult(
        status ->
            untried.forEach(
                target ->
                    deliveries
                        .findByUid(target.deliveryUid())
                        .ifPresent(delivery -> delivery.release(target.dueAt()))));
  }

  private List<String> signing(Webhook webhook, Instant now) {
    try {
      return secrets.signing(webhook, now);
    } catch (IllegalStateException unreadable) {
      log.warn("Webhook {} secret cannot be opened: {}", webhook.getUid(), unreadable.getMessage());
      return null;
    }
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

  private static WebhookView toView(Webhook webhook, String secret, Instant now) {
    return new WebhookView(
        webhook.getUid(),
        webhook.getName(),
        webhook.getUrl(),
        webhook.eventTypeList(),
        webhook.isActive(),
        secret,
        webhook.previousSigns(now) ? webhook.getPreviousUntil() : null,
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
