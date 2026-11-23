package tz.co.pharmacy.modules.access.ports.inbound;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The recommended (preset) permissions of the built-in roles, for a pharmacy that started before
 * the presets changed. The owner's decision of 23 Nov 2026: new pharmacies get the new presets;
 * existing ones keep their roles, and their admin sees a one-time notice to apply the recommended
 * roles or keep their own. Nobody loses access unless the admin chooses it. Current tenant.
 */
public interface RolePresets {

  /** How the pharmacy's built-in roles differ from {@link Permissions#preset}, and the decision. */
  Recommendations recommendations();

  /**
   * Sets every built-in role to its preset, audited as ROLE_PERMISSIONS_CHANGED per role. The Batch
   * 2 rules hold: the Administrator role always has every permission; 409 last_admin when no active
   * member would be left who may manage people; 403 permission_not_held when a role would gain a
   * permission the caller does not hold.
   */
  Recommendations apply();

  /** Keeps the pharmacy's roles as they are; the notice is not shown again for these presets. */
  Recommendations dismiss();

  /**
   * {@code showNotice}: the web shows the one-time notice (some built-in role differs and the admin
   * has not decided for this {@code presetsVersion}). {@code decision} is APPLIED, DISMISSED or
   * null; {@code dismissed} is true when the admin chose to keep their roles.
   */
  record Recommendations(
      String presetsVersion,
      boolean showNotice,
      boolean dismissed,
      String decision,
      Instant decidedAt,
      String decidedBy,
      List<RoleChange> roles) {}

  /** A built-in role and the permissions its preset would add and remove. */
  record RoleChange(
      UUID roleUid, String code, String name, List<String> add, List<String> remove) {}
}
