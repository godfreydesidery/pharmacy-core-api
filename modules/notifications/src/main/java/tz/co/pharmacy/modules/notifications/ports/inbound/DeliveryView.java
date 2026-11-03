package tz.co.pharmacy.modules.notifications.ports.inbound;

import java.time.Instant;
import java.util.UUID;

/**
 * One message in the delivery log. {@code status} is QUEUED, SENDING, SENT or FAILED; {@code to} is
 * the address it was sent to.
 */
public record DeliveryView(
    UUID uid,
    String templateKey,
    Channel channel,
    String to,
    String status,
    String provider,
    int attempts,
    String lastError,
    Instant createdAt,
    Instant sentAt) {}
