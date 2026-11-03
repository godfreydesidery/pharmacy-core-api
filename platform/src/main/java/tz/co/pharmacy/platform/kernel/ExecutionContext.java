package tz.co.pharmacy.platform.kernel;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The actor, tenant, branch and permissions the current thread works under. The edge sets it per
 * request after checking the session, the membership and the branch; event listeners and scheduled
 * work set it to a named platform service. Modules read it; they never set it.
 */
public final class ExecutionContext {

  private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();

  private ExecutionContext() {}

  public static Optional<Frame> current() {
    return Optional.ofNullable(CURRENT.get());
  }

  /** The acting principal. Nothing may be recorded without one. */
  public static Actor actor() {
    return current()
        .map(Frame::actor)
        .orElseThrow(() -> new IllegalStateException("No actor: every change needs one"));
  }

  public static Optional<String> correlationId() {
    return current().map(Frame::correlationId);
  }

  public static Optional<UUID> tenantUid() {
    return current().map(Frame::tenantUid);
  }

  public static UUID requireTenant() {
    return tenantUid()
        .orElseThrow(
            () -> Refusal.forbidden("tenant_required", "Name the tenant with X-Tenant-Id."));
  }

  public static Optional<UUID> branchUid() {
    return current().map(Frame::branchUid);
  }

  /** The branch the request acts in; a branch route always has one (the edge checked it). */
  public static UUID requireBranch() {
    return branchUid()
        .orElseThrow(
            () -> Refusal.forbidden("branch_required", "Name the branch with X-Branch-Id."));
  }

  /**
   * The branches a report may cover: the request's branch when it names one, otherwise the branches
   * the membership may use, or null for all of the tenant's branches.
   */
  public static Set<UUID> reportBranches() {
    Frame frame = CURRENT.get();
    if (frame == null) {
      return null;
    }
    if (frame.branchUid() != null) {
      return Set.of(frame.branchUid());
    }
    return frame.allowedBranches();
  }

  public static boolean rootAdmin() {
    return current().map(Frame::rootAdmin).orElse(false);
  }

  public static boolean permits(String permission) {
    return current().map(frame -> frame.permits(permission)).orElse(false);
  }

  public static void enter(Frame frame) {
    CURRENT.set(frame);
  }

  public static void leave() {
    CURRENT.remove();
  }

  public static <T> T callAs(Frame frame, Supplier<T> work) {
    Frame previous = CURRENT.get();
    CURRENT.set(frame);
    try {
      return work.get();
    } finally {
      if (previous == null) {
        CURRENT.remove();
      } else {
        CURRENT.set(previous);
      }
    }
  }

  /**
   * One request's or one job's context. {@code allowedBranches} is null when the actor may use
   * every branch of the tenant. A root administrator, or a platform service, holds every
   * permission.
   */
  public record Frame(
      Actor actor,
      String correlationId,
      UUID tenantUid,
      UUID branchUid,
      Set<String> permissions,
      Set<UUID> allowedBranches,
      boolean rootAdmin) {

    public static Frame service(String serviceName, String correlationId, UUID tenantUid) {
      return service(serviceName, correlationId, tenantUid, null);
    }

    public static Frame service(
        String serviceName, String correlationId, UUID tenantUid, UUID branchUid) {
      return new Frame(
          Actor.platformService(serviceName),
          correlationId == null ? UUID.randomUUID().toString() : correlationId,
          tenantUid,
          branchUid,
          Set.of(),
          null,
          true);
    }

    public boolean permits(String permission) {
      return rootAdmin || (permissions != null && permissions.contains(permission));
    }

    public Frame withTenant(UUID tenant, UUID branch) {
      return new Frame(
          actor, correlationId, tenant, branch, permissions, allowedBranches, rootAdmin);
    }
  }
}
