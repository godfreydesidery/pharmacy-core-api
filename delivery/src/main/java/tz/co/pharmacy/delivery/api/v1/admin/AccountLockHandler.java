package tz.co.pharmacy.delivery.api.v1.admin;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.modules.access.ports.inbound.AccountLocks;
import tz.co.pharmacy.modules.access.ports.inbound.Permissions;

/** Members locked out after failed sign-ins, and the administrator's unlock. */
@RestController
@RequestMapping("/api/v1/users")
public class AccountLockHandler {

  private final AccountLocks locks;

  public AccountLockHandler(AccountLocks locks) {
    this.locks = locks;
  }

  @GetMapping("/locked")
  @Access(scope = AccessScope.TENANT, permission = Permissions.USERS_MANAGE)
  public ApiResponse<List<AccountLocks.LockedMember>> locked() {
    return ApiResponse.ok(locks.locked(), "Locked accounts retrieved");
  }

  @PostMapping("/{membershipUid}/unlock")
  @Access(scope = AccessScope.TENANT, permission = Permissions.USERS_MANAGE)
  public ApiResponse<Void> unlock(@PathVariable UUID membershipUid) {
    locks.unlock(membershipUid);
    return ApiResponse.ok("Account unlocked");
  }
}
