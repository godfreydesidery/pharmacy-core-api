package tz.co.pharmacy.delivery.api.v1.admin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.delivery.api.Pages;
import tz.co.pharmacy.modules.access.ports.inbound.AddMemberCommand;
import tz.co.pharmacy.modules.access.ports.inbound.ChangeMemberCommand;
import tz.co.pharmacy.modules.access.ports.inbound.CreateManagedLoginCommand;
import tz.co.pharmacy.modules.access.ports.inbound.ManagedLogins;
import tz.co.pharmacy.modules.access.ports.inbound.MemberEnrolment;
import tz.co.pharmacy.modules.access.ports.inbound.MemberView;
import tz.co.pharmacy.modules.access.ports.inbound.Permissions;
import tz.co.pharmacy.modules.access.ports.inbound.StaffDetails;
import tz.co.pharmacy.modules.access.ports.inbound.UserAdministration;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantView;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.persistence.TenantContext;

/** The tenant's users: memberships with their role and branches. */
@RestController
@RequestMapping("/api/v1/users")
public class UserHandler {

  private final UserAdministration users;
  private final MemberEnrolment enrolment;
  private final ManagedLogins managedLogins;
  private final TenantService tenants;
  private final tz.co.pharmacy.delivery.edge.EdgeAccessCache accessCache;

  public UserHandler(
      UserAdministration users,
      MemberEnrolment enrolment,
      ManagedLogins managedLogins,
      TenantService tenants,
      tz.co.pharmacy.delivery.edge.EdgeAccessCache accessCache) {
    this.users = users;
    this.enrolment = enrolment;
    this.managedLogins = managedLogins;
    this.tenants = tenants;
    this.accessCache = accessCache;
  }

  @GetMapping
  @Access(scope = AccessScope.TENANT, permission = Permissions.USERS_MANAGE)
  public ApiResponse<Page<MemberView>> list(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    return ApiResponse.ok(users.members(q, Pages.of(cursor, limit)), "Users retrieved");
  }

  /**
   * 202 with {@code invited: true}: adding a person by email always sends an invitation, whether or
   * not the email has an account, and they join when they accept. A password in the body is
   * ignored. For someone with no email, create a managed login. 422 plan_limit_reached when the
   * plan's users cap is reached: active members and pending invitations both count (INT-6).
   */
  @PostMapping
  @Access(scope = AccessScope.TENANT, permission = Permissions.USERS_MANAGE)
  public ResponseEntity<ApiResponse<InvitedResponse>> add(@RequestBody AddMemberRequest body) {
    if (body == null) {
      throw Refusal.malformed("A user is required.");
    }
    var invitation =
        enrolment.add(
            new AddMemberCommand(
                body.name(),
                body.email(),
                null,
                body.roleUid(),
                Boolean.TRUE.equals(body.allBranches()),
                body.branchUids(),
                body.phone()));
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(
            ApiResponse.ok(
                new InvitedResponse(true, invitation.uid()),
                "An invitation was sent; they join when they accept."));
  }

  /** The invitation sent; the person joins when they accept. */
  public record InvitedResponse(boolean invited, UUID invitationUid) {}

  /**
   * A managed login: signs in as {@code username@CODE} with the temporary password in the answer,
   * shown this once, and must choose its own at first sign-in. 403 direct_creation_disabled when
   * the root administrator has not allowed this pharmacy to create logins; 409 username_taken; 422
   * plan_limit_reached at the plan's users cap.
   */
  @PostMapping("/managed")
  @Access(scope = AccessScope.TENANT, permission = Permissions.USERS_MANAGE)
  public ResponseEntity<ApiResponse<ManagedLogins.Created>> createManaged(
      @RequestBody ManagedLoginRequest body) {
    if (body == null) {
      throw Refusal.malformed("A login is required.");
    }
    TenantView pharmacy = pharmacy();
    if (!pharmacy.directUserCreation()) {
      throw Refusal.forbidden(
          "direct_creation_disabled",
          "This pharmacy invites people by email; it does not create logins for them.");
    }
    ManagedLogins.Created created =
        managedLogins.create(
            new CreateManagedLoginCommand(
                pharmacy.code(),
                body.username(),
                body.name(),
                body.roleUid(),
                Boolean.TRUE.equals(body.allBranches()),
                body.branchUids(),
                body.phone(),
                body.contactEmail()));
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.created(created, "Login created"));
  }

  /**
   * A new temporary password for a managed login of this pharmacy, shown this once: its sessions
   * end and it chooses its own at the next sign-in. 422 not_managed for a person's own account.
   */
  @PostMapping("/{membershipUid}/reset-password")
  @Access(scope = AccessScope.TENANT, permission = Permissions.USERS_MANAGE)
  public ApiResponse<ManagedLogins.Reset> resetPassword(@PathVariable UUID membershipUid) {
    return ApiResponse.ok(managedLogins.resetPassword(membershipUid), "Password reset");
  }

  private TenantView pharmacy() {
    return tenants
        .find(TenantContext.requireTenant())
        .orElseThrow(() -> Refusal.notFound("tenant_not_found", "No such tenant."));
  }

  /**
   * A missing {@code active} keeps the member as they are; {@code true} reactivates a former member
   * and takes a seat (422 plan_limit_reached at the plan's users cap).
   */
  @PutMapping("/{membershipUid}")
  @Access(scope = AccessScope.TENANT, permission = Permissions.USERS_MANAGE)
  public ApiResponse<MemberView> change(
      @PathVariable UUID membershipUid, @RequestBody ChangeMemberRequest body) {
    if (body == null) {
      throw Refusal.malformed("A user is required.");
    }
    // A member's role, branches or deactivation take effect at their next request, on every
    // instance (the edge's access cache, PERF-9).
    return ApiResponse.ok(
        forgetPharmacy(
            users.changeMember(
                membershipUid,
                new ChangeMemberCommand(
                    body.roleUid(),
                    Boolean.TRUE.equals(body.allBranches()),
                    body.branchUids(),
                    // Missing keeps the member as they are; only true reactivates (INT-6).
                    body.active(),
                    body.phone(),
                    body.name(),
                    body.contactEmail(),
                    body.staffNumber() == null
                            && body.jobTitle() == null
                            && body.workPhone() == null
                            && body.workEmail() == null
                        ? null
                        : new StaffDetails(
                            body.staffNumber(),
                            body.jobTitle(),
                            body.workPhone(),
                            body.workEmail())))),
        "User changed");
  }

  /** The pharmacy's cached access, forgotten on every instance once the change is made. */
  private <T> T forgetPharmacy(T changed) {
    accessCache.forgetTenant(TenantContext.requireTenant());
    return changed;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AddMemberRequest(
      String name,
      String email,
      String password,
      UUID roleUid,
      Boolean allBranches,
      List<UUID> branchUids,
      String phone) {}

  /** {@code name} and {@code contactEmail} apply only to a managed login of this pharmacy. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ChangeMemberRequest(
      UUID roleUid,
      Boolean allBranches,
      List<UUID> branchUids,
      Boolean active,
      String phone,
      String name,
      String contactEmail,
      String staffNumber,
      String jobTitle,
      String workPhone,
      String workEmail) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ManagedLoginRequest(
      String username,
      String name,
      UUID roleUid,
      Boolean allBranches,
      List<UUID> branchUids,
      String phone,
      String contactEmail) {}
}
