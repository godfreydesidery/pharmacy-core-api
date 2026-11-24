package tz.co.pharmacy.delivery.api.v1.platform;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.application.onboarding.ports.inbound.OnboardTenantCommand;
import tz.co.pharmacy.application.onboarding.ports.inbound.TenantOnboarding;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.delivery.api.Pages;
import tz.co.pharmacy.delivery.edge.EdgeFilter;
import tz.co.pharmacy.modules.access.ports.inbound.NewUserCommand;
import tz.co.pharmacy.modules.access.ports.inbound.PasswordResetService;
import tz.co.pharmacy.modules.access.ports.inbound.Permissions;
import tz.co.pharmacy.modules.access.ports.inbound.UserAdministration;
import tz.co.pharmacy.modules.access.ports.inbound.UserView;
import tz.co.pharmacy.modules.tenancy.ports.inbound.BranchCommand;
import tz.co.pharmacy.modules.tenancy.ports.inbound.ChangeTenantCommand;
import tz.co.pharmacy.modules.tenancy.ports.inbound.PlatformCounts;
import tz.co.pharmacy.modules.tenancy.ports.inbound.RegisterTenantCommand;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantData;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantView;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.Refusal;

/** The root administrator's console: tenants and root administrators. */
@RestController
@RequestMapping("/api/v1/platform")
public class PlatformHandler {

  private final TenantService tenants;
  private final TenantOnboarding onboarding;
  private final UserAdministration users;
  private final PasswordResetService passwordResets;
  private final tz.co.pharmacy.modules.tenancy.ports.inbound.Subscriptions subscriptions;
  private final TenantData data;
  private final java.time.Clock clock;
  private final int eraseAfterDays;
  private final tz.co.pharmacy.delivery.edge.EdgeAccessCache accessCache;

  public PlatformHandler(
      TenantService tenants,
      TenantOnboarding onboarding,
      UserAdministration users,
      PasswordResetService passwordResets,
      tz.co.pharmacy.modules.tenancy.ports.inbound.Subscriptions subscriptions,
      TenantData data,
      java.time.Clock clock,
      tz.co.pharmacy.delivery.edge.EdgeAccessCache accessCache,
      @Value("${pharmacy.tenancy.erase-after-days:30}") int eraseAfterDays) {
    this.accessCache = accessCache;
    this.data = data;
    this.clock = clock;
    this.eraseAfterDays = eraseAfterDays;
    this.subscriptions = subscriptions;
    this.tenants = tenants;
    this.onboarding = onboarding;
    this.users = users;
    this.passwordResets = passwordResets;
  }

  @GetMapping("/tenants")
  @Access(scope = AccessScope.PLATFORM, permission = Permissions.PLATFORM_MANAGE)
  public ApiResponse<Page<TenantView>> tenants(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    return ApiResponse.ok(tenants.list(q, Pages.of(cursor, limit)), "Tenants retrieved");
  }

  @PostMapping("/tenants")
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.PLATFORM, permission = Permissions.PLATFORM_MANAGE)
  public ApiResponse<RegisteredResponse> register(@RequestBody RegisterTenantRequest body) {
    if (body == null) {
      throw Refusal.malformed("A tenant is required.");
    }
    AdminRequest admin = body.admin();
    TenantOnboarding.Onboarded onboarded =
        onboarding.onboard(
            new OnboardTenantCommand(
                new RegisterTenantCommand(
                    body.code(),
                    body.name(),
                    body.legalName(),
                    body.country(),
                    body.currency(),
                    body.timezone(),
                    body.headOffice()),
                admin == null
                    ? null
                    : new NewUserCommand(admin.name(), admin.email(), admin.password())));
    return ApiResponse.created(
        new RegisteredResponse(onboarded.tenant(), onboarded.adminInvited()),
        onboarded.adminInvited()
            ? "Tenant registered; its administrator already has an account and was invited"
            : "Tenant registered");
  }

  @GetMapping("/tenants/{tenantUid}")
  @Access(scope = AccessScope.PLATFORM, permission = Permissions.PLATFORM_MANAGE)
  public ApiResponse<TenantView> tenant(@PathVariable UUID tenantUid) {
    return ApiResponse.ok(
        tenants
            .find(tenantUid)
            .orElseThrow(() -> Refusal.notFound("tenant_not_found", "No such tenant.")),
        "Tenant retrieved");
  }

  /** The code never changes; the currency only until the pharmacy has posted anything. */
  @PutMapping("/tenants/{tenantUid}")
  @Access(scope = AccessScope.PLATFORM, permission = Permissions.PLATFORM_MANAGE)
  public ApiResponse<TenantView> change(
      @PathVariable UUID tenantUid, @RequestBody ChangeTenantRequest body) {
    if (body == null) {
      throw Refusal.malformed("A tenant is required.");
    }
    return ApiResponse.ok(
        tenants.change(
            tenantUid,
            new ChangeTenantCommand(
                body.name(),
                body.legalName(),
                body.country(),
                body.currency(),
                body.timezone(),
                body.contactName(),
                body.contactEmail(),
                body.contactPhone(),
                body.address(),
                body.directUserCreation())),
        "Tenant changed");
  }

  /**
   * A pharmacy's status changed: its cached access is forgotten on every instance, so a suspension
   * or closure refuses its staff at their next request (PERF-9).
   */
  private <T> T forget(UUID tenantUid, T changed) {
    accessCache.forgetTenant(tenantUid);
    return changed;
  }

  @PostMapping("/tenants/{tenantUid}/suspend")
  @Access(scope = AccessScope.PLATFORM, permission = Permissions.PLATFORM_MANAGE)
  public ApiResponse<TenantView> suspend(
      @PathVariable UUID tenantUid, @RequestBody(required = false) ReasonRequest body) {
    tz.co.pharmacy.modules.tenancy.ports.inbound.SuspensionKind kind =
        body == null || body.kind() == null || body.kind().isBlank()
            ? null
            : tz.co.pharmacy.platform.kernel.Checks.oneOf(
                tz.co.pharmacy.modules.tenancy.ports.inbound.SuspensionKind.class,
                body.kind(),
                "kind");
    return ApiResponse.ok(
        forget(tenantUid, tenants.suspend(tenantUid, kind, body == null ? null : body.reason())),
        "Tenant suspended");
  }

  @PostMapping("/tenants/{tenantUid}/reactivate")
  @Access(scope = AccessScope.PLATFORM, permission = Permissions.PLATFORM_MANAGE)
  public ApiResponse<TenantView> reactivate(@PathVariable UUID tenantUid) {
    TenantView tenant = tenants.reactivate(tenantUid);
    // Its subscription goes on too, or the lifecycle would never invoice it again.
    subscriptions.resumeAfterReactivation(tenantUid);
    return ApiResponse.ok(forget(tenantUid, tenant), "Tenant reactivated");
  }

  /**
   * Closes a pharmacy: nobody works in it, its admins may still read and export, and after the
   * cooling-off ({@code pharmacy.tenancy.erase-after-days}, 30 by default) it may be erased.
   */
  @PostMapping("/tenants/{tenantUid}/close")
  @Access(scope = AccessScope.PLATFORM, permission = Permissions.PLATFORM_MANAGE)
  public ApiResponse<TenantView> close(
      @PathVariable UUID tenantUid, @RequestBody(required = false) ReasonRequest body) {
    TenantView tenant =
        tenants.close(tenantUid, body == null ? null : body.reason(), eraseAfterDays);
    subscriptions.cancel(tenantUid);
    return ApiResponse.ok(forget(tenantUid, tenant), "Tenant closed");
  }

  @PostMapping("/tenants/{tenantUid}/reopen")
  @Access(scope = AccessScope.PLATFORM, permission = Permissions.PLATFORM_MANAGE)
  public ApiResponse<TenantView> reopen(@PathVariable UUID tenantUid) {
    TenantView tenant = tenants.reopen(tenantUid);
    subscriptions.resumeAfterReactivation(tenantUid);
    return ApiResponse.ok(forget(tenantUid, tenant), "Tenant reopened");
  }

  /** A dispute, a regulator's or a court's order: the data may not be erased while it holds. */
  @PostMapping("/tenants/{tenantUid}/legal-hold")
  @Access(scope = AccessScope.PLATFORM, permission = Permissions.PLATFORM_MANAGE)
  public ApiResponse<TenantView> legalHold(
      @PathVariable UUID tenantUid, @RequestBody(required = false) ReasonRequest body) {
    return ApiResponse.ok(
        forget(tenantUid, tenants.legalHold(tenantUid, body == null ? null : body.reason())),
        "Legal hold placed");
  }

  @DeleteMapping("/tenants/{tenantUid}/legal-hold")
  @Access(scope = AccessScope.PLATFORM, permission = Permissions.PLATFORM_MANAGE)
  public ApiResponse<TenantView> liftLegalHold(@PathVariable UUID tenantUid) {
    return ApiResponse.ok(forget(tenantUid, tenants.liftLegalHold(tenantUid)), "Legal hold lifted");
  }

  @GetMapping("/tenants/{tenantUid}/export")
  @Access(scope = AccessScope.PLATFORM, permission = Permissions.PLATFORM_MANAGE)
  public void export(@PathVariable UUID tenantUid, HttpServletResponse response) {
    tz.co.pharmacy.delivery.api.v1.admin.DataExports.write(
        data, tenants, clock, tenantUid, response);
  }

  /** Erases a closed pharmacy's data for good. The body names its code, to confirm. */
  @PostMapping("/tenants/{tenantUid}/erase")
  @Access(scope = AccessScope.PLATFORM, permission = Permissions.PLATFORM_MANAGE)
  public ApiResponse<ErasedResponse> erase(
      @PathVariable UUID tenantUid, @RequestBody(required = false) EraseRequest body) {
    java.util.Map<String, Long> rows =
        data.erase(tenantUid, body == null ? null : body.confirmCode());
    accessCache.forgetTenant(tenantUid);
    return ApiResponse.ok(
        new ErasedResponse(tenants.find(tenantUid).orElseThrow(), rows), "Tenant erased");
  }

  @GetMapping("/root-admins")
  @Access(scope = AccessScope.PLATFORM, permission = Permissions.PLATFORM_MANAGE)
  public ApiResponse<?> rootAdmins() {
    return ApiResponse.ok(Pages.slice(users.rootAdmins()), "Root administrators retrieved");
  }

  @PostMapping("/root-admins")
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.PLATFORM, permission = Permissions.PLATFORM_MANAGE)
  public ApiResponse<UserView> createRootAdmin(@RequestBody AdminRequest body) {
    return ApiResponse.created(
        users.createRootAdmin(
            body == null ? null : new NewUserCommand(body.name(), body.email(), body.password())),
        "Root administrator created");
  }

  @PostMapping("/root-admins/{userUid}/deactivate")
  @Access(scope = AccessScope.PLATFORM, permission = Permissions.PLATFORM_MANAGE)
  public ApiResponse<UserView> deactivateRootAdmin(@PathVariable UUID userUid) {
    UserView deactivated = users.setRootAdminActive(userUid, false);
    accessCache.forgetUser(userUid);
    return ApiResponse.ok(deactivated, "Root administrator deactivated");
  }

  @PostMapping("/root-admins/{userUid}/reactivate")
  @Access(scope = AccessScope.PLATFORM, permission = Permissions.PLATFORM_MANAGE)
  public ApiResponse<UserView> reactivateRootAdmin(@PathVariable UUID userUid) {
    return ApiResponse.ok(
        users.setRootAdminActive(userUid, true), "Root administrator reactivated");
  }

  /** Takes away root rights; the account stays for any pharmacy memberships. */
  @DeleteMapping("/root-admins/{userUid}")
  @Access(scope = AccessScope.PLATFORM, permission = Permissions.PLATFORM_MANAGE)
  public ApiResponse<Void> removeRootAdmin(@PathVariable UUID userUid) {
    users.removeRootAdmin(userUid);
    accessCache.forgetUser(userUid);
    return ApiResponse.ok("Root administrator removed");
  }

  /** Emails the root administrator a password-reset link. */
  @PostMapping("/root-admins/{userUid}/password-reset")
  @Access(scope = AccessScope.PLATFORM, permission = Permissions.PLATFORM_MANAGE)
  public ApiResponse<Void> resetRootAdminPassword(
      @PathVariable UUID userUid, HttpServletRequest request) {
    UserView admin =
        users.rootAdmins().stream()
            .filter(candidate -> candidate.uid().equals(userUid))
            .findFirst()
            .orElseThrow(
                () -> Refusal.notFound("root_admin_not_found", "No such root administrator."));
    passwordResets.request(admin.email(), EdgeFilter.client(request));
    return ApiResponse.ok("Password reset email sent");
  }

  @GetMapping("/overview")
  @Access(scope = AccessScope.PLATFORM, permission = Permissions.PLATFORM_MANAGE)
  public ApiResponse<OverviewResponse> overview() {
    PlatformCounts counts = tenants.counts();
    return ApiResponse.ok(
        new OverviewResponse(
            counts.tenants(),
            counts.activeTenants(),
            counts.suspendedTenants(),
            tenants.branchTotal(),
            users.userCount()),
        "Platform overview retrieved");
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record RegisterTenantRequest(
      String code,
      String name,
      String legalName,
      String country,
      String currency,
      String timezone,
      BranchCommand headOffice,
      AdminRequest admin) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AdminRequest(String name, String email, String password) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  /** {@code kind}: UNPAID, DISPUTE (a legal hold), ABUSE or OTHER (the default). */
  public record ReasonRequest(String reason, String kind) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ChangeTenantRequest(
      String name,
      String legalName,
      String country,
      String currency,
      String timezone,
      String contactName,
      String contactEmail,
      String contactPhone,
      String address,
      Boolean directUserCreation) {}

  /**
   * The pharmacy, and whether its administrator was invited (the email already had an account)
   * rather than made with the password given.
   */
  public record RegisteredResponse(
      @com.fasterxml.jackson.annotation.JsonUnwrapped TenantView tenant, boolean adminInvited) {}

  public record OverviewResponse(
      long tenants, long activeTenants, long suspendedTenants, long branches, long users) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record EraseRequest(String confirmCode) {}

  public record ErasedResponse(TenantView tenant, java.util.Map<String, Long> rows) {}
}
