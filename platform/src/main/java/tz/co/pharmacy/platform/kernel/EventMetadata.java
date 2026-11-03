package tz.co.pharmacy.platform.kernel;

import java.time.Instant;
import java.util.UUID;

/**
 * What every event carries: its own id (listeners use it to ignore a redelivery), its type such as
 * {@code sale.completed}, the tenant and branch it happened in, when, who acted and the request's
 * correlation id.
 */
public record EventMetadata(
    UUID eventId,
    String type,
    UUID tenantUid,
    UUID branchUid,
    Instant occurredAt,
    String actorReference,
    String correlationId) {}
