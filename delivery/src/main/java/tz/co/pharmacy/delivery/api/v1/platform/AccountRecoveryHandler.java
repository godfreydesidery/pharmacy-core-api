package tz.co.pharmacy.delivery.api.v1.platform;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.servlet.http.HttpServletRequest;
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
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.delivery.edge.EdgeFilter;
import tz.co.pharmacy.modules.access.ports.inbound.AccountSecurity;
import tz.co.pharmacy.modules.access.ports.inbound.AccountSecurity.RecoveryView;
import tz.co.pharmacy.modules.access.ports.inbound.Permissions;
import tz.co.pharmacy.modules.access.ports.inbound.UserView;

/**
 * Assisted recovery, for a person who lost their mailbox and has no verified phone. Platform
 * support finds the account by its email and, after checking who the person is (for example their
 * national ID with the pharmacy owner), moves it to a new address. The link works only after a
 * 24-hour hold; meanwhile the old email and phone are told and may cancel it.
 */
@RestController
@RequestMapping("/api/v1/platform/accounts")
public class AccountRecoveryHandler {

  private final AccountSecurity security;

  public AccountRecoveryHandler(AccountSecurity security) {
    this.security = security;
  }

  @GetMapping
  @Access(scope = AccessScope.PLATFORM, permission = Permissions.PLATFORM_MANAGE)
  public ApiResponse<UserView> find(@RequestParam String email) {
    return ApiResponse.ok(security.account(email), "Account retrieved");
  }

  @PostMapping("/{userUid}/recovery")
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.PLATFORM, permission = Permissions.PLATFORM_MANAGE)
  public ApiResponse<RecoveryView> startRecovery(
      @PathVariable UUID userUid, @RequestBody RecoveryRequest body, HttpServletRequest request) {
    RecoveryRequest given = body == null ? new RecoveryRequest(null, false, null) : body;
    return ApiResponse.created(
        security.startRecovery(
            userUid,
            given.newEmail(),
            given.resetTwoFactor(),
            given.reason(),
            EdgeFilter.client(request)),
        "Recovery started");
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record RecoveryRequest(String newEmail, boolean resetTwoFactor, String reason) {}
}
