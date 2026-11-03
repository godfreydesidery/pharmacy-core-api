package tz.co.pharmacy.modules.access.ports.inbound;

import java.time.Instant;
import java.util.UUID;

/** One of the user's live sessions; {@code current} is the one the request came with. */
public record SessionView(
    UUID uid,
    String method,
    String ip,
    String userAgent,
    Instant createdAt,
    Instant lastSeenAt,
    Instant expiresAt,
    boolean current) {}
