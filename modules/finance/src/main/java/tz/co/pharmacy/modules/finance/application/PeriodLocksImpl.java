package tz.co.pharmacy.modules.finance.application;

import java.time.LocalDate;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.finance.domain.PeriodLock;
import tz.co.pharmacy.modules.finance.persistence.PeriodLockRepository;
import tz.co.pharmacy.modules.finance.ports.inbound.PeriodLocks;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.kernel.TenantCalendar;

/**
 * The tenant's period lock, and the guard the journal writer applies to every posting date. The
 * lock row is a tenant entity, so each change lands in the audit log with its before and after.
 */
@Service
@RequiredArgsConstructor
class PeriodLocksImpl implements PeriodLocks {

  /** Postings a person dates; in a locked period they are refused rather than moved. */
  static final Set<String> USER_DATED =
      Set.of(LedgerImpl.EXPENSE, LedgerImpl.OPENING_BALANCE, SupplierPaymentsImpl.SUPPLIER_PAYMENT);

  private final PeriodLockRepository locks;
  private final TenantCalendar calendar;

  @Override
  @Transactional(readOnly = true)
  public PeriodLockView current() {
    return locks
        .findFirstByOrderByIdAsc()
        .map(PeriodLocksImpl::view)
        .orElse(new PeriodLockView(null, null, null));
  }

  @Override
  @Transactional
  public PeriodLockView lockThrough(LocalDate lockedThrough) {
    // The pharmacy's own today (its time zone) always stays open.
    if (lockedThrough != null && !lockedThrough.isBefore(calendar.today())) {
      throw Refusal.invalid("invalid_lock_date", "Lock only days before today.", "lockedThrough");
    }
    PeriodLock lock =
        locks.findFirstByOrderByIdAsc().orElseGet(() -> locks.save(new PeriodLock(lockedThrough)));
    lock.lockThrough(lockedThrough);
    return view(locks.saveAndFlush(lock));
  }

  /**
   * The date a posting is booked on: its own when open; for a locked date, 422 period_locked if a
   * person chose it, else the first open day.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public LocalDate bookingDate(String sourceType, LocalDate date) {
    LocalDate through =
        locks.findFirstByOrderByIdAsc().map(PeriodLock::getLockedThrough).orElse(null);
    if (through == null || date == null || date.isAfter(through)) {
      return date;
    }
    if (USER_DATED.contains(sourceType)) {
      throw Refusal.invalid(
          "period_locked", "The books are closed through " + through + "; choose a later date.");
    }
    return through.plusDays(1);
  }

  private static PeriodLockView view(PeriodLock lock) {
    return new PeriodLockView(
        lock.getLockedThrough(),
        lock.getUpdatedAt() == null ? lock.getCreatedAt() : lock.getUpdatedAt(),
        lock.getUpdatedBy() == null ? lock.getCreatedBy() : lock.getUpdatedBy());
  }
}
