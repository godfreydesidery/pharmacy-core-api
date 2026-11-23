package tz.co.pharmacy.modules.integrations.ports.inbound;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A webhook. {@code secret} ({@code whsec_…}, Standard Webhooks) is shown once, when the webhook is
 * created or its secret rotated, and is null afterwards. {@code previousSecretUntil} is when the
 * secret before the last rotation stops signing deliveries (null when none signs any more).
 */
public record WebhookView(
    UUID uid,
    String name,
    String url,
    List<String> eventTypes,
    boolean active,
    String secret,
    Instant previousSecretUntil,
    Instant createdAt) {}
