package tz.co.pharmacy.modules.access.ports.inbound;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@code status}: PENDING, ACCEPTED, EXPIRED or REVOKED. */
public record InvitationView(
    UUID uid,
    String email,
    String name,
    UUID roleUid,
    String roleName,
    boolean allBranches,
    List<UUID> branchUids,
    String status,
    String invitedBy,
    Instant expiresAt,
    Instant createdAt) {}
