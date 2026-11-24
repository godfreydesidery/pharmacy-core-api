package tz.co.pharmacy.modules.tenancy.ports.inbound;

import java.time.Instant;
import java.util.UUID;

public record BranchView(
    UUID uid,
    String code,
    String name,
    String address,
    String phone,
    String licenceNumber,
    boolean headOffice,
    boolean active,
    Instant createdAt,
    /* The row's version: the ETag of the resource and what If-Match names. */
    long version)
    implements tz.co.pharmacy.platform.kernel.Versioned {}
