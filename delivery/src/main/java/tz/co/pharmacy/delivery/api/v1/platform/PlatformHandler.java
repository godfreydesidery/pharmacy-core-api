package tz.co.pharmacy.delivery.api.v1.platform;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
import tz.co.pharmacy.modules.access.ports.inbound.NewUserCommand;
import tz.co.pharmacy.modules.access.ports.inbound.Permissions;
import tz.co.pharmacy.modules.access.ports.inbound.UserAdministration;
import tz.co.pharmacy.modules.access.ports.inbound.UserView;
import tz.co.pharmacy.modules.tenancy.ports.inbound.BranchCommand;
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

  public PlatformHandler(
      TenantService tenants, TenantOnboarding onboarding, UserAdministration users) {
    this.tenants = tenants;
    this.onboarding = onboarding;
    this.users = users;
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

  public record OverviewResponse(
      long tenants, long activeTenants, long suspendedTenants, long branches, long users) {}
}
