package tz.co.pharmacy.delivery.api.v1.admin;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.delivery.api.Pages;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.persistence.audit.AuditEntryView;
import tz.co.pharmacy.platform.persistence.audit.AuditLog;

/**
 * The audit trail: a tenant's own entries, and the platform's (tenant registry, users, root
 * administrators) for a root administrator. Newest first, keyset-paged.
 */
@RestController
public class AuditHandler {

  private final AuditLog audit;

  public AuditHandler(AuditLog audit) {
    this.audit = audit;
  }

  @GetMapping("/api/v1/audit")
  @Access(scope = AccessScope.TENANT, permission = "settings.manage")
  public ApiResponse<Page<AuditEntryView>> tenant(
      @RequestParam(required = false) String action,
      @RequestParam(required = false) String entityType,
      @RequestParam(required = false) UUID entityUid,
      @RequestParam(required = false) String actor,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    return ApiResponse.ok(
        search(action, entityType, entityUid, actor, from, to, cursor, limit), "Audit");
  }

  @GetMapping("/api/v1/platform/audit")
  @Access(scope = AccessScope.PLATFORM, permission = "platform.manage")
  public ApiResponse<Page<AuditEntryView>> platform(
      @RequestParam(required = false) String action,
      @RequestParam(required = false) String entityType,
      @RequestParam(required = false) UUID entityUid,
      @RequestParam(required = false) String actor,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    return ApiResponse.ok(
        search(action, entityType, entityUid, actor, from, to, cursor, limit), "Audit");
  }

  private Page<AuditEntryView> search(
      String action,
      String entityType,
      UUID entityUid,
      String actor,
      LocalDate from,
      LocalDate to,
      String cursor,
      Integer limit) {
    if (from != null && to != null && from.isAfter(to)) {
      throw Refusal.invalid("invalid_range", "from must not be after to.", "from");
    }
    return audit.search(action, entityType, entityUid, actor, from, to, Pages.of(cursor, limit));
  }
}
