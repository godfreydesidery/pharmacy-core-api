package tz.co.pharmacy.modules.access.ports.inbound;

import java.util.UUID;

/**
 * The signed-in user a session belongs to. {@code twoFactorSetupRequired} is true for a root
 * administrator without two-factor when the platform requires it; {@code passwordChangeRequired}
 * for a managed login still on a temporary password. Until they act, the edge lets them reach only
 * their own account routes.
 */
public record Principal(
    UUID userUid,
    String name,
    String email,
    boolean rootAdmin,
    boolean twoFactorSetupRequired,
    boolean passwordChangeRequired) {

  public Principal(
      UUID userUid, String name, String email, boolean rootAdmin, boolean twoFactorSetupRequired) {
    this(userUid, name, email, rootAdmin, twoFactorSetupRequired, false);
  }

  public Principal(UUID userUid, String name, String email, boolean rootAdmin) {
    this(userUid, name, email, rootAdmin, false, false);
  }
}
