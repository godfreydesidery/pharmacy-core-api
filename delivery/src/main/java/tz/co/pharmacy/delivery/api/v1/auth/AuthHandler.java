package tz.co.pharmacy.delivery.api.v1.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.delivery.edge.EdgeFilter;
import tz.co.pharmacy.modules.access.ports.inbound.AuthenticationService;
import tz.co.pharmacy.modules.access.ports.inbound.Principal;
import tz.co.pharmacy.modules.access.ports.inbound.SessionActivity;
import tz.co.pharmacy.modules.access.ports.inbound.SessionView;
import tz.co.pharmacy.modules.access.ports.inbound.TwoFactorService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.SettingsService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantStatus;
import tz.co.pharmacy.platform.kernel.Actor;
import tz.co.pharmacy.platform.kernel.ExecutionContext;

/**
 * Sign-in (with its second step when two-factor is on), the signed-in user's session and
 * workspaces, sign-out, password change and the user's own sessions.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthHandler {

  private final AuthenticationService authentication;
  private final TwoFactorService twoFactor;
  private final SessionResponses responses;
  private final SettingsService settings;

  public AuthHandler(
      AuthenticationService authentication,
      TwoFactorService twoFactor,
      SessionResponses responses,
      SettingsService settings) {
    this.authentication = authentication;
    this.twoFactor = twoFactor;
    this.responses = responses;
    this.settings = settings;
  }

  /**
   * 201 with the session, or 202 {@code {challengeToken, methods, expiresAt}} when the account has
   * two-factor on: answer it with {@code POST /auth/sign-in/verify}.
   */
  @PostMapping("/sign-in")
  @Access(scope = AccessScope.PUBLIC)
  public ResponseEntity<ApiResponse<?>> signIn(
      @RequestBody SignInRequest body, HttpServletRequest request) {
    return responses.outcome(
        authentication.signIn(
            body == null ? null : body.email(),
            body == null ? null : body.password(),
            EdgeFilter.client(request)));
  }

  /** The second step: a code from the authenticator app, or a recovery code. */
  @PostMapping("/sign-in/verify")
  @Access(scope = AccessScope.PUBLIC)
  public ResponseEntity<ApiResponse<SessionResponse>> verify(
      @RequestBody VerifyRequest body, HttpServletRequest request) {
    return ResponseEntity.status(201)
        .body(
            responses.signedIn(
                twoFactor.verify(
                    body == null ? null : body.challengeToken(),
                    body == null ? null : body.code(),
                    EdgeFilter.client(request))));
  }

  @GetMapping("/me")
  @Access(scope = AccessScope.AUTHENTICATED)
  public ApiResponse<SessionResponse> me(HttpServletRequest request) {
    Actor actor = ExecutionContext.actor();
    Principal principal =
        request.getAttribute(EdgeFilter.PRINCIPAL) instanceof Principal known
            ? known
            : new Principal(
                actor.uid(), actor.name(), actor.reference(), ExecutionContext.rootAdmin());
    return ApiResponse.ok(responses.session(null, null, principal), "Session retrieved");
  }

  /**
   * {@code {"reason": "IDLE"}}: the app signed the person out after the inactivity limit, and the
   * audit log says so; without a body, they signed out themselves.
   */
  @PostMapping("/sign-out")
  @Access(scope = AccessScope.AUTHENTICATED)
  public ApiResponse<Void> signOut(
      @RequestHeader(name = "Authorization", required = false) String authorization,
      @RequestBody(required = false) SignOutRequest body) {
    String token = bearer(authorization);
    if (token != null) {
      authentication.signOut(token, body != null && "IDLE".equalsIgnoreCase(body.reason()));
    }
    return ApiResponse.ok("Signed out");
  }

  /**
   * The person used the app (a key, a click, a touch), reported by the web app at most every half
   * minute: the inactivity clock restarts. In a pharmacy ({@code X-Tenant-Id}) its own limit
   * applies, otherwise the platform's. Other requests never restart the clock, as background ones
   * run with nobody at the screen.
   */
  @PostMapping("/activity")
  @Access(scope = AccessScope.AUTHENTICATED)
  public ApiResponse<SessionActivity> activity(
      @RequestHeader(name = "Authorization", required = false) String authorization) {
    Integer pharmacyMinutes =
        ExecutionContext.tenantUid().isPresent() && !ExecutionContext.rootAdmin()
            ? settings.current().idleMinutes()
            : null;
    return ApiResponse.ok(
        authentication.recordActivity(bearer(authorization), pharmacyMinutes), "Activity recorded");
  }

  /** Changes the password and signs out every other session. */
  @PostMapping("/change-password")
  @Access(scope = AccessScope.AUTHENTICATED)
  public ApiResponse<Void> changePassword(
      @RequestBody ChangePasswordRequest body,
      @RequestHeader(name = "Authorization", required = false) String authorization) {
    authentication.changePassword(
        ExecutionContext.actor().uid(),
        body == null ? null : body.currentPassword(),
        body == null ? null : body.newPassword(),
        bearer(authorization));
    return ApiResponse.ok("Password changed");
  }

  @GetMapping("/sessions")
  @Access(scope = AccessScope.AUTHENTICATED)
  public ApiResponse<List<SessionView>> sessions(
      @RequestHeader(name = "Authorization", required = false) String authorization) {
    return ApiResponse.ok(
        authentication.sessions(ExecutionContext.actor().uid(), bearer(authorization)),
        "Sessions retrieved");
  }

  @DeleteMapping("/sessions/{sessionUid}")
  @Access(scope = AccessScope.AUTHENTICATED)
  public ApiResponse<Void> revokeSession(@PathVariable UUID sessionUid) {
    authentication.revokeSession(ExecutionContext.actor().uid(), sessionUid);
    return ApiResponse.ok("Session signed out");
  }

  /** Signs out of every other device; the current session stays. */
  @PostMapping("/sessions/revoke-others")
  @Access(scope = AccessScope.AUTHENTICATED)
  public ApiResponse<RevokedResponse> revokeOtherSessions(
      @RequestHeader(name = "Authorization", required = false) String authorization) {
    int revoked =
        authentication.revokeOtherSessions(ExecutionContext.actor().uid(), bearer(authorization));
    return ApiResponse.ok(new RevokedResponse(revoked), "Signed out of other devices");
  }

  public record RevokedResponse(int revoked) {}

  private static String bearer(String authorization) {
    if (authorization != null && authorization.startsWith("Bearer ")) {
      return authorization.substring("Bearer ".length()).trim();
    }
    return null;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SignOutRequest(String reason) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SignInRequest(String email, String password) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record VerifyRequest(String challengeToken, String code) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ChangePasswordRequest(String currentPassword, String newPassword) {}

  /** {@code token} and {@code expiresAt} only on sign-in. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record SessionResponse(
      String token, Instant expiresAt, UserResponse user, List<MembershipResponse> memberships) {}

  /**
   * {@code twoFactorSetupRequired}: a root administrator must set up two-factor before using the
   * platform (only when the platform requires it).
   */
  public record UserResponse(
      UUID uid, String name, String email, boolean rootAdmin, boolean twoFactorSetupRequired) {}

  public record MembershipResponse(
      UUID uid,
      UUID tenantUid,
      String tenantCode,
      String tenantName,
      TenantStatus tenantStatus,
      UUID roleUid,
      String roleCode,
      String roleName,
      List<String> permissions,
      boolean allBranches,
      List<BranchResponse> branches) {}

  public record BranchResponse(UUID uid, String code, String name) {}
}
