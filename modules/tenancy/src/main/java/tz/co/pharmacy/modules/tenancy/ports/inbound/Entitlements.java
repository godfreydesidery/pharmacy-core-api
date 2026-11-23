package tz.co.pharmacy.modules.tenancy.ports.inbound;

import java.util.UUID;

/** What a tenant's plan lets it do; the delivery edge asks before a handler runs. */
public interface Entitlements {

  /** 403 plan_feature_unavailable when the tenant's plan lacks the module. */
  void requireFeature(UUID tenantUid, PlanFeature feature);

  /** 422 plan_limit_reached when one more would go over the plan's cap. */
  void requireRoom(UUID tenantUid, PlanLimit limit);

  /** 422 plan_limit_reached when {@code adding} more would go over the plan's cap (an import). */
  void requireRoom(UUID tenantUid, PlanLimit limit, long adding);

  /**
   * As {@link #requireRoom(UUID, PlanLimit, long)} with {@code used} counted by the caller, in its
   * own writing transaction and under its own lock, so that parallel requests cannot pass the cap
   * together (the users cap, INT-6).
   */
  void requireRoomGiven(UUID tenantUid, PlanLimit limit, long used, long adding);
}
