package tz.co.pharmacy.modules.access.ports.inbound;

import java.util.List;
import java.util.UUID;

/**
 * A managed login for the current pharmacy, whose code ({@code tenantCode}) completes the sign-in:
 * {@code username@CODE}. {@code phone} and {@code contactEmail} are where messages to the person
 * go; neither is used to sign in.
 */
public record CreateManagedLoginCommand(
    String tenantCode,
    String username,
    String name,
    UUID roleUid,
    boolean allBranches,
    List<UUID> branchUids,
    String phone,
    String contactEmail) {}
