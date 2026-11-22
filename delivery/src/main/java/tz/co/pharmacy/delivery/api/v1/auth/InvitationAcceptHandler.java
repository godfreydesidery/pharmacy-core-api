package tz.co.pharmacy.delivery.api.v1.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.delivery.edge.EdgeFilter;
import tz.co.pharmacy.modules.access.ports.inbound.InvitationPreview;
import tz.co.pharmacy.modules.access.ports.inbound.InvitationService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantView;
import tz.co.pharmacy.platform.kernel.ExecutionContext;

/**
 * The invitee's side of an invitation: see it, then accept it. Someone new accepts without a
 * session by making their account; someone with an account signs in and joins with it.
 */
@RestController
@RequestMapping("/api/v1/auth/invitations")
public class InvitationAcceptHandler {

  private final InvitationService invitations;
  private final TenantService tenants;
  private final SessionResponses responses;

  public InvitationAcceptHandler(
      InvitationService invitations, TenantService tenants, SessionResponses responses) {
    this.invitations = invitations;
    this.tenants = tenants;
    this.responses = responses;
  }

  @GetMapping("/{token}")
  @Access(scope = AccessScope.PUBLIC)
  public ApiResponse<InvitationPreviewResponse> preview(@PathVariable String token) {
    InvitationPreview preview = invitations.preview(token);
    String tenantName = tenants.find(preview.tenantUid()).map(TenantView::name).orElse(null);
    return ApiResponse.ok(
        new InvitationPreviewResponse(
            preview.email(),
            preview.name(),
            preview.tenantUid(),
            tenantName,
            preview.roleName(),
            preview.invitedBy(),
            preview.expiresAt(),
            preview.status(),
            preview.existingUser()),
        "Invitation retrieved");
  }

  /**
   * Accepts with a new account (name and password): 201 with the session. 409 account_exists when
   * the invited email already has one: sign in, then join.
   */
  @PostMapping("/accept")
  @Access(scope = AccessScope.PUBLIC)
  public ResponseEntity<ApiResponse<?>> accept(
      @RequestBody AcceptRequest body, HttpServletRequest request) {
    return responses.outcome(
        invitations.accept(
            body == null ? null : body.token(),
            body == null ? null : body.name(),
            body == null ? null : body.password(),
            EdgeFilter.client(request)));
  }

  /**
   * The signed-in account accepts, whatever email it signs in with: it joins the pharmacy. 409
   * already_a_member; 422 managed_login_cannot_join; 422 invitation_not_open.
   */
  @PostMapping("/join")
  @Access(scope = AccessScope.AUTHENTICATED)
  public ApiResponse<JoinedResponse> join(@RequestBody JoinRequest body) {
    InvitationService.Joined joined =
        invitations.join(body == null ? null : body.token(), ExecutionContext.actor().uid());
    String tenantName = tenants.find(joined.tenantUid()).map(TenantView::name).orElse(null);
    return ApiResponse.ok(
        new JoinedResponse(joined.tenantUid(), tenantName, joined.roleName()), "Joined");
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AcceptRequest(String token, String name, String password) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record JoinRequest(String token) {}

  public record JoinedResponse(UUID tenantUid, String tenantName, String roleName) {}

  public record InvitationPreviewResponse(
      String email,
      String name,
      UUID tenantUid,
      String tenantName,
      String roleName,
      String invitedBy,
      Instant expiresAt,
      String status,
      boolean existingUser) {}
}
