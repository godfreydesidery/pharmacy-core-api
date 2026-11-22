package tz.co.pharmacy.delivery.api.v1.admin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.delivery.api.CountsAgainst;
import tz.co.pharmacy.delivery.api.Pages;
import tz.co.pharmacy.modules.access.ports.inbound.InvitationService;
import tz.co.pharmacy.modules.access.ports.inbound.InvitationView;
import tz.co.pharmacy.modules.access.ports.inbound.InviteCommand;
import tz.co.pharmacy.modules.tenancy.ports.inbound.PlanLimit;
import tz.co.pharmacy.platform.kernel.Page;

/** Inviting people to the tenant by email, with a role and branch access. */
@RestController
@RequestMapping("/api/v1/users/invitations")
public class InvitationHandler {

  private final InvitationService invitations;

  public InvitationHandler(InvitationService invitations) {
    this.invitations = invitations;
  }

  @GetMapping
  @Access(scope = AccessScope.TENANT, permission = "users.manage")
  public ApiResponse<Page<InvitationView>> list(
      @RequestParam(required = false) Boolean pending,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    return ApiResponse.ok(
        invitations.list(Boolean.TRUE.equals(pending), Pages.of(cursor, limit)),
        "Invitations retrieved");
  }

  @CountsAgainst(PlanLimit.USERS)
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.TENANT, permission = "users.manage")
  public ApiResponse<InvitationView> invite(@RequestBody InviteRequest body) {
    InviteRequest request =
        body == null ? new InviteRequest(null, null, null, false, List.of()) : body;
    return ApiResponse.created(
        invitations.invite(
            new InviteCommand(
                request.name(),
                request.email(),
                request.roleUid(),
                request.allBranches(),
                request.branchUids())),
        "Invitation sent");
  }

  @DeleteMapping("/{invitationUid}")
  @Access(scope = AccessScope.TENANT, permission = "users.manage")
  public ApiResponse<Void> revoke(@PathVariable UUID invitationUid) {
    invitations.revoke(invitationUid);
    return ApiResponse.ok("Invitation revoked");
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record InviteRequest(
      String name, String email, UUID roleUid, boolean allBranches, List<UUID> branchUids) {}
}
