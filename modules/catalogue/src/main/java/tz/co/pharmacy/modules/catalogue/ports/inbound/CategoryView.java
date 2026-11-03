package tz.co.pharmacy.modules.catalogue.ports.inbound;

import java.time.Instant;
import java.util.UUID;

/** A category; {@code path} names its ancestors, such as "Medical Equipment › Mobility Aids". */
public record CategoryView(
    UUID uid, String name, String description, Instant createdAt, UUID parentUid, String path) {}
