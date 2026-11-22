package tz.co.pharmacy.modules.tenancy.ports.inbound;

import java.time.Instant;
import java.util.UUID;

public record TenantView(
    UUID uid,
    String code,
    String name,
    String legalName,
    String country,
    String currency,
    String timezone,
    TenantStatus status,
    String statusReason,
    int branchCount,
    Instant createdAt,
    String contactName,
    String contactEmail,
    String contactPhone,
    String address,
    boolean directUserCreation,
    /* Why it is suspended (null while active): UNPAID, DISPUTE, ABUSE or OTHER. */
    SuspensionKind suspensionKind) {}
