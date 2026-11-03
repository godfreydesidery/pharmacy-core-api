package tz.co.pharmacy.modules.notifications.ports.inbound;

import java.time.Instant;
import java.util.UUID;

/** One in-app message; {@code severity} is info, warning or critical. */
public record InAppView(
    UUID uid,
    String title,
    String body,
    String link,
    String severity,
    boolean read,
    Instant createdAt) {}
