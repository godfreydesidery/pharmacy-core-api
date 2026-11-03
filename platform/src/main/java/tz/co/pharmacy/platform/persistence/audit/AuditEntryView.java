package tz.co.pharmacy.platform.persistence.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One audit entry: who did what to which record, when, in which tenant and branch, and the values
 * before and after (secrets masked). {@code action} is CREATE, UPDATE or DELETE for record changes,
 * or a security event such as SIGN_IN, SIGN_IN_FAILED, ACCOUNT_LOCKED, TWO_FACTOR_ENABLED,
 * PASSWORD_RESET, ROLE_PERMISSIONS_CHANGED or ROOT_ACTED_IN_TENANT.
 */
public record AuditEntryView(
    UUID uid,
    Instant at,
    String actor,
    String action,
    String entityType,
    UUID entityUid,
    UUID tenantUid,
    UUID branchUid,
    Map<String, Object> before,
    Map<String, Object> after,
    String correlationId) {}
