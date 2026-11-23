package tz.co.pharmacy.modules.notifications.ports.inbound;

import java.time.Instant;
import java.util.UUID;

/**
 * One message as it was sent, for support: the rendered subject and text with secrets in links
 * (token, code and key query values) masked, the recipient, and the provider's last answer.
 */
public record DeliveryDetail(
    UUID uid,
    String templateKey,
    Channel channel,
    String recipientName,
    String to,
    String locale,
    String subject,
    String body,
    String status,
    String provider,
    int attempts,
    String lastError,
    Instant createdAt,
    Instant sentAt,
    String reason) {}
