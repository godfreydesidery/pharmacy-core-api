package tz.co.pharmacy.delivery.api.v1.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.delivery.edge.EdgeFilter;
import tz.co.pharmacy.modules.access.ports.inbound.PasskeyOptions;
import tz.co.pharmacy.modules.access.ports.inbound.PasskeyService;
import tz.co.pharmacy.modules.access.ports.inbound.PasskeyView;
import tz.co.pharmacy.modules.access.ports.inbound.PasswordResetService;
import tz.co.pharmacy.modules.access.ports.inbound.SecurityOverview;
import tz.co.pharmacy.modules.access.ports.inbound.TwoFactorService;
import tz.co.pharmacy.modules.access.ports.inbound.TwoFactorSetup;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * The signed-in user's own security: two-factor with an authenticator app and recovery codes,
 * passkeys, and the public steps of a passkey sign-in and a password reset.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class SecurityHandler {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final TwoFactorService twoFactor;
  private final PasskeyService passkeys;
  private final PasswordResetService resets;
  private final SessionResponses responses;

  public SecurityHandler(
      TwoFactorService twoFactor,
      PasskeyService passkeys,
      PasswordResetService resets,
      SessionResponses responses) {
    this.twoFactor = twoFactor;
    this.passkeys = passkeys;
    this.resets = resets;
    this.responses = responses;
  }

  @GetMapping("/security")
  @Access(scope = AccessScope.AUTHENTICATED)
  public ApiResponse<SecurityOverview> security() {
    return ApiResponse.ok(twoFactor.overview(self()), "Security retrieved");
  }

  /** A new authenticator secret: show {@code otpauthUri} as a QR code, then confirm a code. */
  @PostMapping("/2fa/setup")
  @Access(scope = AccessScope.AUTHENTICATED)
  public ApiResponse<TwoFactorSetup> setUp() {
    return ApiResponse.ok(twoFactor.begin(self()), "Scan the code with your authenticator app");
  }

  /** Turns two-factor on and returns the 10 recovery codes, shown once. */
  @PostMapping("/2fa/enable")
  @Access(scope = AccessScope.AUTHENTICATED)
  public ApiResponse<RecoveryCodesResponse> enable(@RequestBody CodeRequest body) {
    return ApiResponse.ok(
        new RecoveryCodesResponse(twoFactor.enable(self(), code(body))), "Two-factor is on");
  }

  @PostMapping("/2fa/disable")
  @Access(scope = AccessScope.AUTHENTICATED)
  public ApiResponse<Void> disable(@RequestBody CodeRequest body) {
    twoFactor.disable(self(), code(body));
    return ApiResponse.ok("Two-factor is off");
  }

  @PostMapping("/2fa/recovery-codes")
  @Access(scope = AccessScope.AUTHENTICATED)
  public ApiResponse<RecoveryCodesResponse> recoveryCodes(@RequestBody CodeRequest body) {
    return ApiResponse.ok(
        new RecoveryCodesResponse(twoFactor.regenerateRecoveryCodes(self(), code(body))),
        "New recovery codes");
  }

  @GetMapping("/passkeys")
  @Access(scope = AccessScope.AUTHENTICATED)
  public ApiResponse<List<PasskeyView>> passkeys() {
    return ApiResponse.ok(passkeys.list(self()), "Passkeys retrieved");
  }

  /**
   * The creation options: pass {@code options.publicKey} to {@code
   * PublicKeyCredential.parseCreationOptionsFromJSON}, then post the credential's {@code toJSON()}.
   */
  @PostMapping("/passkeys/register/options")
  @Access(scope = AccessScope.AUTHENTICATED)
  public ApiResponse<PasskeyOptionsResponse> registrationOptions() {
    return ApiResponse.ok(options(passkeys.startRegistration(self())), "Passkey options");
  }

  @PostMapping("/passkeys/register")
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.AUTHENTICATED)
  public ApiResponse<PasskeyView> register(@RequestBody PasskeyRequest body) {
    PasskeyRequest request = required(body);
    return ApiResponse.created(
        passkeys.finishRegistration(
            self(), request.ceremonyUid(), json(request.credential()), request.name()),
        "Passkey added");
  }

  @DeleteMapping("/passkeys/{passkeyUid}")
  @Access(scope = AccessScope.AUTHENTICATED)
  public ApiResponse<Void> removePasskey(@PathVariable UUID passkeyUid) {
    passkeys.remove(self(), passkeyUid);
    return ApiResponse.ok("Passkey removed");
  }

  /** The request options of a usernameless passkey sign-in. */
  @PostMapping("/passkeys/sign-in/options")
  @Access(scope = AccessScope.PUBLIC)
  public ApiResponse<PasskeyOptionsResponse> signInOptions() {
    return ApiResponse.ok(options(passkeys.startSignIn()), "Passkey options");
  }

  @PostMapping("/passkeys/sign-in")
  @Access(scope = AccessScope.PUBLIC)
  public ResponseEntity<ApiResponse<AuthHandler.SessionResponse>> passkeySignIn(
      @RequestBody PasskeyRequest body, HttpServletRequest request) {
    PasskeyRequest answer = required(body);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            responses.signedIn(
                passkeys.finishSignIn(
                    answer.ceremonyUid(), json(answer.credential()), EdgeFilter.client(request))));
  }

  /** Always 202, whether or not the email has an account. */
  @PostMapping("/password-reset")
  @ResponseStatus(HttpStatus.ACCEPTED)
  @Access(scope = AccessScope.PUBLIC)
  public ApiResponse<Void> requestReset(
      @RequestBody EmailRequest body, HttpServletRequest request) {
    resets.request(body == null ? null : body.email(), EdgeFilter.client(request));
    return new ApiResponse<>(
        true,
        "ACCEPTED",
        "If the email has an account, a reset link is on its way",
        java.time.Instant.now(),
        null);
  }

  @PostMapping("/password-reset/complete")
  @Access(scope = AccessScope.PUBLIC)
  public ApiResponse<Void> completeReset(@RequestBody ResetRequest body) {
    resets.complete(body == null ? null : body.token(), body == null ? null : body.newPassword());
    return ApiResponse.ok("Password changed; sign in with the new one");
  }

  private static UUID self() {
    return ExecutionContext.actor().uid();
  }

  private static String code(CodeRequest body) {
    return body == null ? null : body.code();
  }

  private static PasskeyRequest required(PasskeyRequest body) {
    if (body == null || body.ceremonyUid() == null || body.credential() == null) {
      throw Refusal.invalid(
          "invalid_credential", "Send the ceremonyUid and the credential.", "credential");
    }
    return body;
  }

  private static PasskeyOptionsResponse options(PasskeyOptions options) {
    return new PasskeyOptionsResponse(options.ceremonyUid(), JSON.readTree(options.optionsJson()));
  }

  private static String json(JsonNode node) {
    return JSON.writeValueAsString(node);
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CodeRequest(String code) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record EmailRequest(String email) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ResetRequest(String token, String newPassword) {}

  /** {@code credential}: the browser's {@code PublicKeyCredential.toJSON()}. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PasskeyRequest(UUID ceremonyUid, JsonNode credential, String name) {}

  /** {@code options}: {@code {"publicKey": {...}}} in the WebAuthn JSON form. */
  public record PasskeyOptionsResponse(UUID ceremonyUid, JsonNode options) {}

  public record RecoveryCodesResponse(List<String> recoveryCodes) {}
}
