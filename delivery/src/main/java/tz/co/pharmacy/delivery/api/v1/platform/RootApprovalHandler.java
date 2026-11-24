package tz.co.pharmacy.delivery.api.v1.platform;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.delivery.edge.EdgeFilter;
import tz.co.pharmacy.modules.access.ports.inbound.AccountSecurity;
import tz.co.pharmacy.modules.access.ports.inbound.Permissions;
import tz.co.pharmacy.modules.access.ports.inbound.UserAdministration;
import tz.co.pharmacy.modules.access.ports.inbound.UserView;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantData;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantStatus;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantView;
import tz.co.pharmacy.platform.approval.RootApprovals;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * Two root administrators for lifting a legal hold and erasing a pharmacy (FRD-18, owner's decision
 * of 24 Nov 2026). One asks with a reason; a different one approves with their password (and their
 * second-factor code), within 24 hours, and the action runs at once, once. With a single active
 * root administrator both actions are refused: add a second one first.
 */
@RestController
@RequestMapping("/api/v1/platform/approvals")
public class RootApprovalHandler {

  private final RootApprovals approvals;
  private final UserAdministration users;
  private final AccountSecurity security;
  private final TenantService tenants;
  private final TenantData data;
  private final java.time.Clock clock;
  private final tz.co.pharmacy.delivery.edge.EdgeAccessCache accessCache;

  public RootApprovalHandler(
      RootApprovals approvals,
      UserAdministration users,
      AccountSecurity security,
      TenantService tenants,
      TenantData data,
      java.time.Clock clock,
      tz.co.pharmacy.delivery.edge.EdgeAccessCache accessCache) {
    this.approvals = approvals;
    this.users = users;
    this.security = security;
    this.tenants = tenants;
    this.data = data;
    this.clock = clock;
    this.accessCache = accessCache;
  }

  /** The requests waiting for a second root administrator, or ({@code status=ALL}) every one. */
  @GetMapping
  @Access(scope = AccessScope.PLATFORM, permission = Permissions.PLATFORM_MANAGE)
  public ApiResponse<List<RootApprovalView>> list(
      @RequestParam(required = false, defaultValue = "PENDING") String status) {
    return ApiResponse.ok(
        approvals.list(!"ALL".equalsIgnoreCase(status)).stream().map(this::view).toList(),
        "Approval requests retrieved");
  }

  /**
   * Asks for a second root administrator's approval. 409 second_root_admin_required with only one
   * active root administrator; 422 not_under_legal_hold, confirm_code_mismatch or erase_refused
   * when the action could not run anyway.
   */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.PLATFORM, permission = Permissions.PLATFORM_MANAGE)
  public ApiResponse<RootApprovalView> request(@RequestBody RootApprovalRequest body) {
    if (body == null) {
      throw Refusal.malformed("An action, a pharmacy and a reason are required.");
    }
    UUID me = ExecutionContext.actor().uid();
    long others =
        users.rootAdmins().stream()
            .filter(UserView::active)
            .filter(root -> !root.uid().equals(me))
            .count();
    if (others == 0) {
      throw Refusal.conflict(
          "second_root_admin_required",
          "Lifting a legal hold and erasing a pharmacy need a second root administrator to"
              + " approve; add one first.");
    }
    if (body.tenantUid() == null) {
      throw Refusal.invalid("tenant_required", "Name the pharmacy.", "tenantUid");
    }
    TenantView tenant =
        tenants
            .find(body.tenantUid())
            .orElseThrow(() -> Refusal.notFound("tenant_not_found", "No such pharmacy."));
    String action = body.action() == null ? "" : body.action().trim().toUpperCase();
    if (RootApprovals.LIFT_LEGAL_HOLD.equals(action) && !tenant.legalHold()) {
      throw Refusal.invalid(
          "not_under_legal_hold", "The pharmacy is not under a legal hold.", "tenantUid");
    }
    if (RootApprovals.ERASE_TENANT.equals(action)) {
      erasable(tenant, body.confirmCode());
    }
    return ApiResponse.ok(
        view(
            approvals.request(
                action, tenant.uid(), body.reason(), me, ExecutionContext.actor().name())),
        "Waiting for a second root administrator");
  }

  /**
   * A second root administrator approves with their password (and their code when they have
   * two-factor), and the action runs now. A wrong password counts like a failed sign-in.
   */
  @PostMapping("/{uid}/approve")
  @Access(scope = AccessScope.PLATFORM, permission = Permissions.PLATFORM_MANAGE)
  public ApiResponse<RootApprovalView> approve(
      @PathVariable UUID uid,
      @RequestBody(required = false) ApproveBody body,
      @RequestHeader(name = "Authorization", required = false) String authorization,
      HttpServletRequest request) {
    UUID me = ExecutionContext.actor().uid();
    RootApprovals.Request asked = approvals.find(uid);
    if (asked.requestedByUid().equals(me)) {
      // Before the password, so the person who asked learns nothing by trying.
      throw Refusal.forbidden(
          "approver_is_requester",
          "A different root administrator must approve what you asked for.");
    }
    String token =
        authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
            ? authorization.substring(7).trim()
            : null;
    security.confirmIdentity(
        me,
        token,
        body == null ? null : body.password(),
        body == null ? null : body.code(),
        EdgeFilter.client(request));
    RootApprovals.Request claimed = approvals.claim(uid, me, ExecutionContext.actor().name());
    java.util.Map<String, Long> rows = null;
    try {
      if (RootApprovals.LIFT_LEGAL_HOLD.equals(claimed.action())) {
        tenants.liftLegalHold(claimed.tenantUid());
      } else {
        TenantView tenant = tenants.find(claimed.tenantUid()).orElseThrow();
        rows = data.erase(claimed.tenantUid(), tenant.code());
      }
    } catch (RuntimeException refused) {
      approvals.release(uid, refused.getMessage());
      throw refused;
    }
    accessCache.forgetTenant(claimed.tenantUid());
    RootApprovalView done = view(approvals.find(uid));
    return ApiResponse.ok(
        done.withOutcome(tenants.find(claimed.tenantUid()).orElse(null), rows),
        "Approved and done");
  }

  @PostMapping("/{uid}/cancel")
  @Access(scope = AccessScope.PLATFORM, permission = Permissions.PLATFORM_MANAGE)
  public ApiResponse<RootApprovalView> cancel(@PathVariable UUID uid) {
    return ApiResponse.ok(
        view(approvals.cancel(uid, ExecutionContext.actor().uid())), "Request cancelled");
  }

  /** The erasure's own checks, at the request already, with the same reasons. */
  private void erasable(TenantView tenant, String confirmCode) {
    if (confirmCode == null || !tenant.code().equalsIgnoreCase(confirmCode.trim())) {
      throw Refusal.invalid(
          "confirm_code_mismatch", "Type the pharmacy's code to confirm.", "confirmCode");
    }
    if (tenant.status() != TenantStatus.CLOSED) {
      throw Refusal.invalid(
          "erase_refused", "Only a closed pharmacy can be erased; close it first.", "status");
    }
    if (tenant.legalHold()) {
      throw Refusal.invalid(
          "erase_refused", "The pharmacy is under a legal hold; lift it first.", "legalHold");
    }
    if (tenant.eraseAfter() == null || tenant.eraseAfter().isAfter(clock.instant())) {
      throw Refusal.invalid(
          "erase_refused",
          "The pharmacy is in its cooling-off period until " + tenant.eraseAfter() + ".",
          "eraseAfter");
    }
  }

  private RootApprovalView view(RootApprovals.Request request) {
    TenantView tenant = tenants.find(request.tenantUid()).orElse(null);
    return new RootApprovalView(
        request.uid(),
        request.action(),
        request.tenantUid(),
        tenant == null ? null : tenant.code(),
        tenant == null ? null : tenant.name(),
        request.reason(),
        request.status(),
        request.requestedByUid(),
        request.requestedByName(),
        request.requestedAt(),
        request.expiresAt(),
        request.approvedByUid(),
        request.approvedByName(),
        request.approvedAt(),
        request.cancelledAt(),
        null,
        null);
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record RootApprovalRequest(
      String action, UUID tenantUid, String reason, String confirmCode) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ApproveBody(String password, String code) {}

  public record RootApprovalView(
      UUID uid,
      String action,
      UUID tenantUid,
      String tenantCode,
      String tenantName,
      String reason,
      String status,
      UUID requestedByUid,
      String requestedByName,
      Instant requestedAt,
      Instant expiresAt,
      UUID approvedByUid,
      String approvedByName,
      Instant approvedAt,
      Instant cancelledAt,
      /* After an approval ran: the pharmacy as it now is, and an erasure's rows by table. */
      TenantView tenant,
      java.util.Map<String, Long> rows) {

    RootApprovalView withOutcome(TenantView after, java.util.Map<String, Long> erased) {
      return new RootApprovalView(
          uid,
          action,
          tenantUid,
          tenantCode,
          tenantName,
          reason,
          status,
          requestedByUid,
          requestedByName,
          requestedAt,
          expiresAt,
          approvedByUid,
          approvedByName,
          approvedAt,
          cancelledAt,
          after,
          erased);
    }
  }
}
