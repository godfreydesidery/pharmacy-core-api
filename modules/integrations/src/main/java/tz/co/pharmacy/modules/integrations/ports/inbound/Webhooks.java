package tz.co.pharmacy.modules.integrations.ports.inbound;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;

/** The current tenant's webhooks and their deliveries. */
public interface Webhooks {

  /**
   * sale.completed, sale.returned, goods.received, stock.adjusted, stock.low, product.changed,
   * tenant.registered.
   */
  List<String> eventTypes();

  List<WebhookView> list();

  /** An https URL (or http://localhost); a 32-byte random secret, returned once. */
  WebhookView create(WebhookCommand command);

  WebhookView change(UUID webhookUid, WebhookCommand command);

  void delete(UUID webhookUid);

  Page<DeliveryView> deliveries(UUID webhookUid, PageQuery query);

  /**
   * Called by composition reactions, in the event's tenant: records a delivery for each active
   * webhook subscribed to the type, then sends each as a CloudEvent signed with HMAC-SHA256 ({@code
   * X-Pharmacy-Signature}), up to 3 attempts with backoff, recording the outcome. {@code data} is
   * the event; its {@code metadata} is left out of the payload.
   */
  void dispatch(UUID eventId, String eventType, Instant occurredAt, Object data);

  /**
   * Sends again the current pharmacy's failed deliveries whose next attempt is due (up to 100),
   * each outside any transaction; returns how many were tried. A delivery whose webhook is gone or
   * switched off is given up.
   */
  int retryDue();
}
