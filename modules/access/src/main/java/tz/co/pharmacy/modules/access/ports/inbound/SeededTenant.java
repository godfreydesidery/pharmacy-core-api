package tz.co.pharmacy.modules.access.ports.inbound;

import java.util.UUID;

/**
 * A new pharmacy's roles, seeded. {@code admin} is its administrator, made with the given password;
 * null when the email already has an account, which is not added without its consent: the caller
 * invites it with {@code adminRoleUid}.
 */
public record SeededTenant(MemberView admin, UUID adminRoleUid) {}
