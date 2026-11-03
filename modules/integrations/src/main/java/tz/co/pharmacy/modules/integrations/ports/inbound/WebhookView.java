package tz.co.pharmacy.modules.integrations.ports.inbound;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A webhook; {@code secret} is shown once, when it is created, and is null afterwards. */
public record WebhookView(
    UUID uid,
    String name,
    String url,
    List<String> eventTypes,
    boolean active,
    String secret,
    Instant createdAt) {}
