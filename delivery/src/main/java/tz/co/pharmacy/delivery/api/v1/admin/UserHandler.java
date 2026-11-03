package tz.co.pharmacy.delivery.api.v1.admin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.delivery.api.Pages;
import tz.co.pharmacy.modules.access.ports.inbound.AddMemberCommand;
import tz.co.pharmacy.modules.access.ports.inbound.ChangeMemberCommand;
import tz.co.pharmacy.modules.access.ports.inbound.MemberView;
import tz.co.pharmacy.modules.access.ports.inbound.Permissions;
import tz.co.pharmacy.modules.access.ports.inbound.UserAdministration;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.Refusal;

/** The tenant's users: memberships with their role and branches. */
@RestController
@RequestMapping("/api/v1/users")
public class UserHandler {

  private final UserAdministration users;

  public UserHandler(UserAdministration users) {
    this.users = users;
  }

  @GetMapping
  @Access(scope = AccessScope.TENANT, permission = Permissions.USERS_MANAGE)
  public ApiResponse<Page<MemberView>> list(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    return ApiResponse.ok(users.members(q, Pages.of(cursor, limit)), "Users retrieved");
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.TENANT, permission = Permissions.USERS_MANAGE)
  public ApiResponse<MemberView> add(@RequestBody AddMemberRequest body) {
    if (body == null) {
      throw Refusal.malformed("A user is required.");
    }
    return ApiResponse.created(
        users.addMember(
            new AddMemberCommand(
                body.name(),
                body.email(),
                body.password(),
                body.roleUid(),
                Boolean.TRUE.equals(body.allBranches()),
                body.branchUids())),
        "User added");
  }

  @PutMapping("/{membershipUid}")
  @Access(scope = AccessScope.TENANT, permission = Permissions.USERS_MANAGE)
  public ApiResponse<MemberView> change(
      @PathVariable UUID membershipUid, @RequestBody ChangeMemberRequest body) {
    if (body == null) {
      throw Refusal.malformed("A user is required.");
    }
    return ApiResponse.ok(
        users.changeMember(
            membershipUid,
            new ChangeMemberCommand(
                body.roleUid(),
                Boolean.TRUE.equals(body.allBranches()),
                body.branchUids(),
                body.active() == null || body.active())),
        "User changed");
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AddMemberRequest(
      String name,
      String email,
      String password,
      UUID roleUid,
      Boolean allBranches,
      List<UUID> branchUids) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ChangeMemberRequest(
      UUID roleUid, Boolean allBranches, List<UUID> branchUids, Boolean active) {}
}
