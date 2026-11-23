package tz.co.pharmacy.modules.finance.application;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.finance.domain.PeriodLock;
import tz.co.pharmacy.modules.finance.persistence.PeriodLockRepository;
import tz.co.pharmacy.modules.finance.ports.inbound.PeriodLocks;
import tz.co.pharmacy.platform.approval.ApprovalActions;
import tz.co.pharmacy.platform.approval.Approvals;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.kernel.TenantCalendar;
import tz.co.pharmacy.platform.persistence.audit.AuditLog;

/**
 * The tenant's period lock, and the guard the journal writer applies to every posting date. Moving
 * the lock forward closes more days and needs nothing more than the permission; moving it back or
 * removing it reopens closed books and always needs a second person's approval (PERIOD_UNLOCK,
 * adversarial review FRD-10). Every change is audited as PERIOD_LOCK_CHANGED with its before and
 * after (the row's own change is audited too); the row's version makes two changes at once refuse
 * one (409 stale_version) rather than both pass on the same "before".
 */
@Service
@RequiredArgsConstructor
class PeriodLocksImpl implements PeriodLocks {

  /** Postings a person dates; in a locked period they are refused rather than moved. */
  static final Set<String> USER_DATED =
      Set.of(LedgerImpl.EXPENSE, LedgerImpl.OPENING_BALANCE, SupplierPaymentsImpl.SUPPLIER_PAYMENT);

  private final PeriodLockRepository locks;
  private final TenantCalendar calendar;
  private final Approvals approvals;
  private final AuditLog audit;

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
    PeriodLock existing = locks.findFirstByOrderByIdAsc().orElse(null);
    LocalDate before = existing == null ? null : existing.getLockedThrough();
    if (Objects.equals(before, lockedThrough)) {
      return existing == null ? new PeriodLockView(null, null, null) : view(existing);
    }
    if (before != null && (lockedThrough == null || lockedThrough.isBefore(before))) {
      approvals.require(
          ApprovalActions.PERIOD_UNLOCK,
          null,
          "Period lock " + before + " to " + (lockedThrough == null ? "none" : lockedThrough));
    }
    PeriodLock lock = existing == null ? locks.save(new PeriodLock(lockedThrough)) : existing;
    lock.lockThrough(lockedThrough);
    PeriodLock saved = locks.saveAndFlush(lock);
    Map<String, Object> was = new LinkedHashMap<>();
    was.put("lockedThrough", before == null ? null : before.toString());
    Map<String, Object> now = new LinkedHashMap<>();
    now.put("lockedThrough", lockedThrough == null ? null : lockedThrough.toString());
    audit.record("PERIOD_LOCK_CHANGED", "PeriodLock", saved.getUid(), was, now);
    return view(saved);
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
