package tz.co.pharmacy.delivery.api.v1.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.delivery.api.Pages;
import tz.co.pharmacy.delivery.edge.EdgeFilter;
import tz.co.pharmacy.modules.access.ports.inbound.AccountSecurity;
import tz.co.pharmacy.modules.access.ports.inbound.AccountSecurity.SecurityEventView;
import tz.co.pharmacy.platform.kernel.Clocks;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Page;

/**
 * The person's own account security: email change (confirmed from the new mailbox), phone
 * verification, reset by phone, completing or cancelling an assisted recovery, and their security
 * history. Account routes: the pharmacy named in a request is ignored.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AccountSecurityHandler {

  private final AccountSecurity security;

  public AccountSecurityHandler(AccountSecurity security) {
    this.security = security;
  }

  /** Always 202: the same answer whether or not the new address already has an account. */
  @PostMapping("/email-change")
  @ResponseStatus(HttpStatus.ACCEPTED)
  @Access(scope = AccessScope.AUTHENTICATED)
  public ApiResponse<Void> requestEmailChange(
      @RequestBody EmailChangeRequest body, HttpServletRequest request) {
    EmailChangeRequest given = body == null ? new EmailChangeRequest(null, null, null) : body;
    security.requestEmailChange(
        self(), given.newEmail(), given.password(), given.code(), EdgeFilter.client(request));
    return accepted("Check the new mailbox for a link to confirm the change");
  }

  /** From the link in the new mailbox; a recovery also sets the new password. */
  @PostMapping("/email-change/confirm")
  @Access(scope = AccessScope.PUBLIC)
  public ApiResponse<Void> confirmEmail(@RequestBody ConfirmRequest body) {
    security.confirmEmail(
        body == null ? null : body.token(), body == null ? null : body.newPassword());
    return ApiResponse.ok("Email changed; sign in with the new address");
  }

  /** From the notice sent to the old email or phone. */
  @PostMapping("/recovery/cancel")
  @Access(scope = AccessScope.PUBLIC)
  public ApiResponse<Void> cancelRecovery(@RequestBody TokenRequest body) {
    security.cancelRecovery(body == null ? null : body.token());
    return ApiResponse.ok("Recovery cancelled; nothing was changed");
  }

  @PostMapping("/phone")
  @ResponseStatus(HttpStatus.ACCEPTED)
  @Access(scope = AccessScope.AUTHENTICATED)
  public ApiResponse<Void> sendPhoneCode(
      @RequestBody PhoneRequest body, HttpServletRequest request) {
    security.sendPhoneCode(self(), body == null ? null : body.phone(), EdgeFilter.client(request));
    return accepted("A code is on its way by SMS");
  }

  @PostMapping("/phone/verify")
  @Access(scope = AccessScope.AUTHENTICATED)
  public ApiResponse<Void> verifyPhone(@RequestBody CodeOnly body) {
    security.verifyPhone(self(), body == null ? null : body.code());
    return ApiResponse.ok("Phone verified");
  }

  @PostMapping("/phone/remove")
  @Access(scope = AccessScope.AUTHENTICATED)
  public ApiResponse<Void> removePhone(@RequestBody PasswordOnly body) {
    security.removePhone(self(), body == null ? null : body.password());
    return ApiResponse.ok("Phone removed");
  }

  /** Always 202, whether or not the number is an account's verified phone. */
  @PostMapping("/password-reset/phone")
  @ResponseStatus(HttpStatus.ACCEPTED)
  @Access(scope = AccessScope.PUBLIC)
  public ApiResponse<Void> requestPhoneReset(
      @RequestBody PhoneRequest body, HttpServletRequest request) {
    security.requestPhoneReset(body == null ? null : body.phone(), EdgeFilter.client(request));
    return accepted("If the number is verified on an account, a code is on its way by SMS");
  }

  @PostMapping("/password-reset/phone/complete")
  @Access(scope = AccessScope.PUBLIC)
  public ApiResponse<Void> completePhoneReset(@RequestBody PhoneResetRequest body) {
    PhoneResetRequest given = body == null ? new PhoneResetRequest(null, null, null) : body;
    security.completePhoneReset(given.phone(), given.code(), given.newPassword());
    return ApiResponse.ok("Password changed; sign in with the new one");
  }

  @GetMapping("/security/history")
  @Access(scope = AccessScope.AUTHENTICATED)
  public ApiResponse<Page<SecurityEventView>> history(
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    return ApiResponse.ok(security.history(self(), Pages.of(cursor, limit)), "History retrieved");
  }

  private static UUID self() {
    return ExecutionContext.actor().uid();
  }

  private static ApiResponse<Void> accepted(String message) {
    return new ApiResponse<>(true, "ACCEPTED", message, Clocks.now(), null);
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record EmailChangeRequest(String newEmail, String password, String code) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ConfirmRequest(String token, String newPassword) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record TokenRequest(String token) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PhoneRequest(String phone) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CodeOnly(String code) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PasswordOnly(String password) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PhoneResetRequest(String phone, String code, String newPassword) {}
}
