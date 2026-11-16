package tz.co.pharmacy.modules.access.ports.inbound;

import java.time.Instant;
import java.util.UUID;

public record UserView(
    UUID uid,
    String name,
    String email,
    boolean rootAdmin,
    boolean active,
    Instant lastSignInAt,
    Instant createdAt,
    String phone) {}
