package tz.co.pharmacy.delivery.api.v1.auth;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.delivery.api.v1.auth.AuthHandler.BranchResponse;
import tz.co.pharmacy.delivery.api.v1.auth.AuthHandler.MembershipResponse;
import tz.co.pharmacy.delivery.api.v1.auth.AuthHandler.SessionResponse;
import tz.co.pharmacy.delivery.api.v1.auth.AuthHandler.UserResponse;
import tz.co.pharmacy.modules.access.ports.inbound.AuthenticationService;
import tz.co.pharmacy.modules.access.ports.inbound.MembershipGrant;
import tz.co.pharmacy.modules.access.ports.inbound.Permissions;
import tz.co.pharmacy.modules.access.ports.inbound.Principal;
import tz.co.pharmacy.modules.access.ports.inbound.SignInOutcome;
import tz.co.pharmacy.modules.access.ports.inbound.SignInResult;
import tz.co.pharmacy.modules.tenancy.ports.inbound.BranchService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.BranchView;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantView;
import tz.co.pharmacy.platform.kernel.Clocks;
import tz.co.pharmacy.platform.persistence.TenantContext;

/**
 * Builds the Session body every way of signing in answers with (password, second factor, passkey,
 * accepted invitation), and the 202 challenge when a second factor is still due.
 */
@Component
public class SessionResponses {

  private final AuthenticationService authentication;
  private final TenantService tenants;
  private final BranchService branches;

  public SessionResponses(
      AuthenticationService authentication, TenantService tenants, BranchService branches) {
    this.authentication = authentication;
    this.tenants = tenants;
    this.branches = branches;
  }

  /** 201 with the session, or 202 with the challenge. */
  public ResponseEntity<ApiResponse<?>> outcome(SignInOutcome outcome) {
    return switch (outcome) {
      case SignInOutcome.Signed signed ->
          ResponseEntity.status(HttpStatus.CREATED).body(signedIn(signed.session()));
      case SignInOutcome.ChallengeRequired challenge ->
          ResponseEntity.status(HttpStatus.ACCEPTED)
              .body(
                  new ApiResponse<>(
                      true,
                      "ACCEPTED",
                      "A second factor is required",
                      Clocks.now().truncatedTo(ChronoUnit.MILLIS),
                      new ChallengeResponse(
                          challenge.challengeToken(), challenge.methods(), challenge.expiresAt())));
    };
  }

  public ApiResponse<SessionResponse> signedIn(SignInResult result) {
    return ApiResponse.created(
        session(result.token(), result.expiresAt(), result.principal()), "Signed in");
  }

  public SessionResponse session(String token, Instant expiresAt, Principal principal) {
    List<MembershipResponse> memberships =
        authentication.memberships(principal.userUid()).stream()
            .filter(MembershipGrant::active)
            .map(this::membership)
            .flatMap(Optional::stream)
            .sorted(Comparator.comparing(MembershipResponse::tenantName))
            .toList();
    return new SessionResponse(
        token,
        expiresAt,
        new UserResponse(
            principal.userUid(),
            principal.name(),
            principal.email(),
            principal.rootAdmin(),
            principal.twoFactorSetupRequired(),
            principal.passwordChangeRequired()),
        memberships);
  }

  private Optional<MembershipResponse> membership(MembershipGrant grant) {
    Optional<TenantView> tenant = tenants.find(grant.tenantUid());
    if (tenant.isEmpty()) {
      return Optional.empty();
    }
    List<BranchResponse> allowed =
        TenantContext.callAs(grant.tenantUid(), branches::list).stream()
            .filter(BranchView::active)
            .filter(branch -> grant.allBranches() || grant.branchUids().contains(branch.uid()))
            .map(branch -> new BranchResponse(branch.uid(), branch.code(), branch.name()))
            .toList();
    List<String> permissions =
        Permissions.ALL.stream().filter(grant.permissions()::contains).toList();
    TenantView view = tenant.get();
    return Optional.of(
        new MembershipResponse(
            grant.membershipUid(),
            view.uid(),
            view.code(),
            view.name(),
            view.status(),
            grant.roleUid(),
            grant.roleCode(),
            grant.roleName(),
            permissions,
            grant.allBranches(),
            allowed,
            view.directUserCreation()));
  }

  /** {@code methods}: TOTP, RECOVERY_CODE and, when the user has one, PASSKEY. */
  public record ChallengeResponse(String challengeToken, List<String> methods, Instant expiresAt) {}
}
