package tz.co.pharmacy.modules.access.ports.inbound;

import java.util.UUID;

/**
 * The signed-in user a session belongs to. {@code twoFactorSetupRequired} is true for a root
 * administrator without two-factor when the platform requires it: until they set it up, the edge
 * lets them reach only their own account routes.
 */
public record Principal(
    UUID userUid, String name, String email, boolean rootAdmin, boolean twoFactorSetupRequired) {

  public Principal(UUID userUid, String name, String email, boolean rootAdmin) {
    this(userUid, name, email, rootAdmin, false);
  }
}
