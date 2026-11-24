package tz.co.pharmacy.platform.persistence.audit;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;

/**
 * The audit trail. Record changes are written automatically by {@link EntityAuditListener}; modules
 * record security events explicitly. Entries are append-only and belong to the current tenant, or
 * to the platform when the transaction names no tenant.
 */
public interface AuditLog {

  /**
   * Records an event in the caller's transaction, or in its own when there is none. The actor,
   * tenant, branch and correlation id come from the execution context; secrets are masked.
   */
  void record(
      String action,
      String entityType,
      UUID entityUid,
      Map<String, Object> before,
      Map<String, Object> after);

  /**
   * As {@link #record(String, String, UUID, Map, Map)} with its outcome given: SUCCESS, or FAILURE
   * for an attempt that was refused or failed. Without it the outcome follows the action's name
   * (SIGN_IN_FAILED is a FAILURE). The entry also notes the request's client address, user agent
   * and session (STD-22).
   */
  default void record(
      String action,
      String entityType,
      UUID entityUid,
      Map<String, Object> before,
      Map<String, Object> after,
      String outcome) {
    record(action, entityType, entityUid, before, after);
  }

  /**
   * The current scope's entries, newest first, filtered by any of action (such as SIGN_IN), entity
   * type, entity uid, actor (contains, any case) and an inclusive date range (UTC days).
   */
  Page<AuditEntryView> search(
      String action,
      String entityType,
      UUID entityUid,
      String actor,
      LocalDate from,
      LocalDate to,
      PageQuery query);

  /**
   * The security events of one account in the current scope, newest first: sign-ins, failed
   * attempts, password, email, phone and second-factor changes. Record changes (CREATE, UPDATE,
   * DELETE) are left out. Read on the account's own routes, which name no tenant, it is the
   * platform's record of the person and nothing a pharmacy wrote.
   */
  Page<AuditEntryView> securityEvents(UUID userUid, PageQuery query);

  /**
   * The current scope's entries in [from, before), oldest first, whose action is one of {@code
   * actions} or whose record is of one of {@code entityTypes}; at most {@code limit}. For digests
   * (FRD-19), not for paging.
   */
  java.util.List<AuditEntryView> between(
      java.time.Instant from,
      java.time.Instant before,
      java.util.Collection<String> actions,
      java.util.Collection<String> entityTypes,
      int limit);
}
