package tz.co.pharmacy.modules.access.ports.inbound;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code status}: PENDING, ACCEPTED, EXPIRED or REVOKED. {@code acceptedAs}: the sign-in of the
 * account that accepted, which may differ from the email invited.
 */
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
    Instant createdAt,
    String acceptedAs) {}
