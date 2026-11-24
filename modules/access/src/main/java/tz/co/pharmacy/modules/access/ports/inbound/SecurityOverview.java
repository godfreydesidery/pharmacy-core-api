package tz.co.pharmacy.modules.access.ports.inbound;

import java.time.Instant;

/**
 * The second factors of an account, for its security page. {@code twoFactorRequired}: the platform
 * requires two-factor of this account (a root administrator). {@code pendingEmail}: a new sign-in
 * email waiting for its mailbox to confirm it. {@code minPasswordLength}: what a new password needs
 * now, 8 with a second factor and 15 without (STD-21). {@code passwordLongEnoughAlone}: the current
 * password is known to have 15 characters, so two-factor may be turned off.
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
    String pendingEmail,
    int minPasswordLength,
    boolean passwordLongEnoughAlone) {}
