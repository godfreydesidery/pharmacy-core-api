package tz.co.pharmacy.modules.finance.ports.inbound;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Closing the books: nothing is posted on or before the locked date. A back-dated expense, supplier
 * payment or opening balance in a locked period is refused (422 period_locked); a sale, return,
 * receipt or other posting that arrives late for a locked day is booked on the first open day
 * instead, so trading never stops.
 */
public interface PeriodLocks {

  PeriodLockView current();

  /**
   * Locks the books through {@code lockedThrough}, or unlocks them with null. 422 invalid_lock_date
   * unless the date is before today, so the current day stays open.
   */
  PeriodLockView lockThrough(LocalDate lockedThrough);

  record PeriodLockView(LocalDate lockedThrough, Instant changedAt, String changedBy) {}
}
