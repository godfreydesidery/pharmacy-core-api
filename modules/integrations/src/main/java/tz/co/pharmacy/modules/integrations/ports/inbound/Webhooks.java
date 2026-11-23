package tz.co.pharmacy.modules.integrations.ports.inbound;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;

/** The current tenant's webhooks and their deliveries. */
public interface Webhooks {

  /** The most webhooks one pharmacy may have. */
  int MAX_WEBHOOKS = 10;

  /**
   * sale.completed, sale.returned, goods.received, stock.adjusted, stock.low, product.changed,
   * tenant.registered.
   */
  List<String> eventTypes();

  List<WebhookView> list();

  /**
   * An https URL to a public host (422 {@code webhook_url_not_allowed}); a new Standard Webhooks
   * secret ({@code whsec_} + base64 of 32 random bytes), returned once and kept sealed. At most
   * {@link #MAX_WEBHOOKS} per pharmacy: 422 {@code webhook_limit}.
   */
  WebhookView create(WebhookCommand command);

  WebhookView change(UUID webhookUid, WebhookCommand command);

  /**
   * A new signing secret, returned once. For the next 24 hours deliveries carry a signature with
   * the new secret and one with the old, so the receiver can switch over without missing any.
   */
  WebhookView rotateSecret(UUID webhookUid);

  void delete(UUID webhookUid);

  Page<DeliveryView> deliveries(UUID webhookUid, PageQuery query);

  /**
   * Called by composition reactions, in the event's tenant: records a delivery for each active
   * webhook subscribed to the type, then sends each, side by side, as a CloudEvent signed as
   * Standard Webhooks define ({@code webhook-id}, {@code webhook-timestamp}, {@code
   * webhook-signature}), up to 3 attempts with backoff, recording the outcome. {@code data} is the
   * event; its {@code metadata} is left out of the payload.
   */
  void dispatch(UUID eventId, String eventType, Instant occurredAt, Object data);

  /**
   * Sends again the current pharmacy's failed deliveries whose next attempt is due (up to 100),
   * each outside any transaction, one lane per webhook side by side; returns how many were tried.
   * The due deliveries are claimed with {@code FOR UPDATE SKIP LOCKED} and a lease, so two
   * instances never send the same one. A delivery whose webhook is gone or switched off is given
   * up. Webhook secrets still in plain text are sealed on the way.
   */
  int retryDue();
}
