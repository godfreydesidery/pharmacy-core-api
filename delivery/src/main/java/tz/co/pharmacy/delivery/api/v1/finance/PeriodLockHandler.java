package tz.co.pharmacy.delivery.api.v1.finance;

import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.modules.finance.ports.inbound.PeriodLocks;
import tz.co.pharmacy.modules.finance.ports.inbound.PeriodLocks.PeriodLockView;

/**
 * Closing the books through a date: {@code finance.period_lock} (not finance.manage, adversarial
 * review FRD-10); moving the lock back or removing it also needs an approval (PERIOD_UNLOCK).
 */
@RestController
@RequestMapping("/api/v1/finance/period-lock")
@RequiredArgsConstructor
public class PeriodLockHandler {

  private final PeriodLocks periodLocks;

  @GetMapping
  @Access(scope = AccessScope.TENANT_OPTIONAL_BRANCH, permission = "finance.view")
  public ApiResponse<PeriodLockView> current() {
    return ApiResponse.ok(periodLocks.current(), "Period lock retrieved");
  }

  @PutMapping
  @Access(scope = AccessScope.TENANT_OPTIONAL_BRANCH, permission = "finance.period_lock")
  public ApiResponse<PeriodLockView> lock(@RequestBody LockRequest body) {
    return ApiResponse.ok(
        periodLocks.lockThrough(body == null ? null : body.lockedThrough()), "Period lock changed");
  }

  public record LockRequest(LocalDate lockedThrough) {}
}
