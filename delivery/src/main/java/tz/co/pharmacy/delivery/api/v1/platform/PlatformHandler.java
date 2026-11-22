package tz.co.pharmacy.delivery.api.v1.platform;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
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

  public PlatformHandler(
      TenantService tenants,
      TenantOnboarding onboarding,
      UserAdministration users,
      PasswordResetService passwordResets) {
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
  public ApiResponse<TenantView> register(@RequestBody RegisterTenantRequest body) {
    if (body == null) {
      throw Refusal.malformed("A tenant is required.");
    }
    AdminRequest admin = body.admin();
    TenantView tenant =
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
    return ApiResponse.created(tenant, "Tenant registered");
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

  @PostMapping("/tenants/{tenantUid}/suspend")
  @Access(scope = AccessScope.PLATFORM, permission = Permissions.PLATFORM_MANAGE)
  public ApiResponse<TenantView> suspend(
      @PathVariable UUID tenantUid, @RequestBody(required = false) ReasonRequest body) {
    return ApiResponse.ok(
        tenants.suspend(tenantUid, body == null ? null : body.reason()), "Tenant suspended");
  }

  @PostMapping("/tenants/{tenantUid}/reactivate")
  @Access(scope = AccessScope.PLATFORM, permission = Permissions.PLATFORM_MANAGE)
  public ApiResponse<TenantView> reactivate(@PathVariable UUID tenantUid) {
    return ApiResponse.ok(tenants.reactivate(tenantUid), "Tenant reactivated");
  }

  @GetMapping("/root-admins")
  @Access(scope = AccessScope.PLATFORM, permission = Permissions.PLATFORM_MANAGE)
  public ApiResponse<List<UserView>> rootAdmins() {
    return ApiResponse.ok(users.rootAdmins(), "Root administrators retrieved");
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
    return ApiResponse.ok(
        users.setRootAdminActive(userUid, false), "Root administrator deactivated");
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
  public record ReasonRequest(String reason) {}

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

  public record OverviewResponse(
      long tenants, long activeTenants, long suspendedTenants, long branches, long users) {}
}
