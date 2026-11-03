package tz.co.pharmacy.modules.access.ports.inbound;

/**
 * A new TOTP secret, in base32 for manual entry and as an {@code otpauth://} URI for a QR code; it
 * takes effect once a code from it is confirmed.
 */
public record TwoFactorSetup(String secret, String otpauthUri) {}
