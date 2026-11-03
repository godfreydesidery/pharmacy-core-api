package tz.co.pharmacy.modules.access.ports.inbound;

import java.util.Set;
import java.util.UUID;

/** What a membership lets its user do in one tenant: the role's permissions and the branches. */
public record MembershipGrant(
    UUID membershipUid,
    UUID tenantUid,
    UUID roleUid,
    String roleCode,
    String roleName,
    Set<String> permissions,
    boolean allBranches,
    Set<UUID> branchUids,
    boolean active) {}
