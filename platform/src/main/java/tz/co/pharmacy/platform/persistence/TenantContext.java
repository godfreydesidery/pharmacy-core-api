package tz.co.pharmacy.platform.persistence;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The tenant, by uid, whose rows the current thread may see, and the signed-in user, whose own
 * memberships it may see in every tenant. Every transaction copies them into the PostgreSQL
 * settings the row-level security policies read ({@link TenantAwareTransactionManager}), so a query
 * that forgets its tenant predicate still sees one tenant only. With no tenant, every tenant-scoped
 * table looks empty: isolation fails closed.
 */
public final class TenantContext {

  private static final ThreadLocal<UUID> TENANT_UID = new ThreadLocal<>();
  private static final ThreadLocal<UUID> USER_UID = new ThreadLocal<>();

  private TenantContext() {}

  public static Optional<UUID> tenantUid() {
    return Optional.ofNullable(TENANT_UID.get());
  }

  public static UUID requireTenant() {
    return tenantUid()
        .orElseThrow(() -> new IllegalStateException("No tenant: a tenant row needs one"));
  }

  public static Optional<UUID> userUid() {
    return Optional.ofNullable(USER_UID.get());
  }

  public static void enter(UUID tenantUid, UUID userUid) {
    set(TENANT_UID, tenantUid);
    set(USER_UID, userUid);
  }

  public static void leave() {
    TENANT_UID.remove();
    USER_UID.remove();
  }

  /**
   * Runs work as the given tenant. Call it outside a transaction, so that the work's own
   * transaction starts with this tenant; inside one, use {@link TenantSwitch}.
   */
  public static <T> T callAs(UUID tenantUid, Supplier<T> work) {
    UUID previous = TENANT_UID.get();
    set(TENANT_UID, tenantUid);
    try {
      return work.get();
    } finally {
      set(TENANT_UID, previous);
    }
  }

  /** Runs work able to see the user's own memberships in every tenant. */
  public static <T> T callAsUser(UUID userUid, Supplier<T> work) {
    UUID previous = USER_UID.get();
    set(USER_UID, userUid);
    try {
      return work.get();
    } finally {
      set(USER_UID, previous);
    }
  }

  static void setTenant(UUID tenantUid) {
    set(TENANT_UID, tenantUid);
  }

  private static void set(ThreadLocal<UUID> holder, UUID value) {
    if (value == null) {
      holder.remove();
    } else {
      holder.set(value);
    }
  }
}
