package tz.co.pharmacy.modules.access.ports.inbound;

import java.time.Instant;

/**
 * The second factors of an account, for its security page. {@code twoFactorRequired}: the platform
 * requires two-factor of this account (a root administrator).
 */
public record SecurityOverview(
    boolean twoFactorEnabled,
    Instant twoFactorEnabledAt,
    int recoveryCodesLeft,
    int passkeys,
    boolean twoFactorRequired) {}
