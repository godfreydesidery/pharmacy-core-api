package tz.co.pharmacy.modules.access.ports.inbound;

import java.time.Instant;
import java.util.UUID;

/**
 * What the invitee sees before accepting. {@code existingUser}: the email already has an account,
 * so accepting asks for its current password rather than a new one.
 */
public record InvitationPreview(
    String email,
    String name,
    UUID tenantUid,
    String roleName,
    String invitedBy,
    Instant expiresAt,
    String status,
    boolean existingUser) {}
