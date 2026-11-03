package tz.co.pharmacy.modules.integrations.ports.inbound;

import java.time.Instant;
import java.util.UUID;

public record DeliveryView(
    UUID uid,
    String eventType,
    UUID eventId,
    DeliveryStatus status,
    Integer responseStatus,
    int attempts,
    String lastError,
    Instant createdAt,
    Instant deliveredAt) {}
