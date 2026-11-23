package tz.co.pharmacy.modules.access.ports.inbound;

import java.time.Instant;
import java.util.UUID;

/**
 * The signed-in user a session belongs to. {@code twoFactorSetupRequired} is true for a root
 * administrator without two-factor when the platform requires it; {@code passwordChangeRequired}
 * for a managed login still on a temporary password. Until they act, the edge lets them reach only
 * their own account routes. {@code secondFactor}: this session proved a second factor (an
 * authenticator or recovery code, or a passkey with user verification), as a pharmacy that requires
 * two-factor asks (G16). {@code verifiedAt}: when the session last proved its password or passkey,
 * at sign-in or by confirming it again; a sensitive account change needs it to be recent.
 */
public record Principal(
    UUID userUid,
    String name,
    String email,
    boolean rootAdmin,
    boolean twoFactorSetupRequired,
    boolean passwordChangeRequired,
    UUID sessionUid,
    boolean secondFactor,
    Instant verifiedAt) {

  public Principal(
      UUID userUid,
      String name,
      String email,
      boolean rootAdmin,
      boolean twoFactorSetupRequired,
      boolean passwordChangeRequired,
      UUID sessionUid) {
    this(
        userUid,
        name,
        email,
        rootAdmin,
        twoFactorSetupRequired,
        passwordChangeRequired,
        sessionUid,
        false,
        null);
  }

  public Principal(
      UUID userUid,
      String name,
      String email,
      boolean rootAdmin,
      boolean twoFactorSetupRequired,
      boolean passwordChangeRequired) {
    this(userUid, name, email, rootAdmin, twoFactorSetupRequired, passwordChangeRequired, null);
  }

  public Principal(
      UUID userUid, String name, String email, boolean rootAdmin, boolean twoFactorSetupRequired) {
    this(userUid, name, email, rootAdmin, twoFactorSetupRequired, false, null);
  }

  public Principal(UUID userUid, String name, String email, boolean rootAdmin) {
    this(userUid, name, email, rootAdmin, false, false, null);
  }

  /** The same principal, of session {@code uid}. */
  public Principal inSession(UUID uid) {
    return new Principal(
        userUid,
        name,
        email,
        rootAdmin,
        twoFactorSetupRequired,
        passwordChangeRequired,
        uid,
        secondFactor,
        verifiedAt);
  }

  /** Whether the session proved its password or passkey at or after {@code since}. */
  public boolean verifiedSince(Instant since) {
    return verifiedAt != null && !verifiedAt.isBefore(since);
  }
}
