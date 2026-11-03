package tz.co.pharmacy.modules.files.ports.inbound;

import java.time.Instant;
import java.util.UUID;

/** A stored file of the current tenant, such as a prescription image attached to a sale. */
public record FileView(
    UUID uid,
    String name,
    String contentType,
    long size,
    String ownerType,
    UUID ownerUid,
    String purpose,
    String sha256,
    Instant createdAt,
    String createdBy) {}
