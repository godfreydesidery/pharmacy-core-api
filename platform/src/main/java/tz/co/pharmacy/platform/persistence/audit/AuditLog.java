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
   * The current scope's entries, newest first, filtered by any of entity type, entity uid, actor
   * (contains, any case) and an inclusive date range (UTC days).
   */
  Page<AuditEntryView> search(
      String entityType,
      UUID entityUid,
      String actor,
      LocalDate from,
      LocalDate to,
      PageQuery query);
}
