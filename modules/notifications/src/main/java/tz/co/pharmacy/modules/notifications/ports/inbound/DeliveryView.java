package tz.co.pharmacy.modules.notifications.ports.inbound;

import java.time.Instant;
import java.util.UUID;

/**
 * One message in the delivery log. {@code status} is QUEUED, SENDING, SENT or FAILED; {@code to} is
 * the address it was sent to; {@code reason} a stable code when the platform refused it rather than
 * a provider failing ({@code sms_cap_reached}: the plan's SMS a month on the platform's account are
 * used).
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
    Instant sentAt,
    String reason) {}
