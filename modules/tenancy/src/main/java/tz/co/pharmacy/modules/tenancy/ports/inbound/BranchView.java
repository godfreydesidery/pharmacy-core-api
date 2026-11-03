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
    Instant createdAt) {}
