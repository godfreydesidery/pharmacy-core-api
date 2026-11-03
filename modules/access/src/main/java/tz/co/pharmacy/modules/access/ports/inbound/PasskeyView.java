package tz.co.pharmacy.modules.access.ports.inbound;

import java.time.Instant;
import java.util.UUID;

public record PasskeyView(
    UUID uid, String name, String transports, Instant createdAt, Instant lastUsedAt) {}
