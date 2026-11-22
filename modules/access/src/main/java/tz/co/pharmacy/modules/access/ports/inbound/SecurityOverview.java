package tz.co.pharmacy.modules.access.ports.inbound;

import java.time.Instant;

/**
 * The second factors of an account, for its security page. {@code twoFactorRequired}: the platform
 * requires two-factor of this account (a root administrator). {@code pendingEmail}: a new sign-in
 * email waiting for its mailbox to confirm it.
 */
public record SecurityOverview(
    boolean twoFactorEnabled,
    Instant twoFactorEnabledAt,
    int recoveryCodesLeft,
    int passkeys,
    boolean twoFactorRequired,
    String email,
    String phone,
    boolean phoneVerified,
    boolean managed,
    String pendingEmail) {}
