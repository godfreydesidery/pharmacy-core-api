package tz.co.pharmacy.modules.inventory.application;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tz.co.pharmacy.modules.inventory.ports.outbound.ProductCatalogue;
import tz.co.pharmacy.platform.approval.ApprovalActions;
import tz.co.pharmacy.platform.approval.Approvals;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * The owner's stock controls of 23 Nov 2026 (FRD-5, 11, 12, 15): which approval a write-off needs,
 * a write-off's note, and one approval per document.
 *
 * <p>A document that writes off several lines (a destruction, a stock-take, a transfer's shortfall)
 * is approved once, for its total, and its lines are then written off {@link #covered}; a file
 * import is approved once per action for the whole file ({@link #requireOnce}). Neither keeps
 * anything beyond the call or the transaction, so any instance may serve any request.
 */
@Component
class StockControls {

  /** Set while a document's own approval covers the write-offs of its lines. */
  private static final ThreadLocal<Boolean> COVERED = new ThreadLocal<>();

  private static final String ONCE = StockControls.class.getName() + ".approved.";

  private final Approvals approvals;
  private final ProductCatalogue catalogue;

  StockControls(Approvals approvals, ProductCatalogue catalogue) {
    this.approvals = approvals;
    this.catalogue = catalogue;
  }

  /** Whether the product is a controlled medicine now. */
  boolean controlled(UUID productUid) {
    return "CONTROLLED".equals(catalogue.schedule(productUid));
  }

  /** The dispensing class, ranked: OTC 0, PRESCRIPTION 1, CONTROLLED 2 (unknown counts as OTC). */
  int rank(UUID productUid) {
    String schedule = catalogue.schedule(productUid);
    if ("CONTROLLED".equals(schedule)) {
      return 2;
    }
    return "PRESCRIPTION".equals(schedule) ? 1 : 0;
  }

  /** A write-off's note: 422 note_required when it has none. */
  static String requireNote(String note, String field) {
    String text = Checks.optionalText(note, field, 500);
    if (text == null) {
      throw Refusal.invalid(
          "note_required", "Say why the stock is written off (a note is required).", field);
    }
    return text;
  }

  /** The manager whose approval an action used. */
  record Approver(String name, UUID uid) {}

  /**
   * Lets a write-off of {@code value} at cost go ahead: a controlled medicine always needs a
   * manager (CONTROLLED_WRITE_OFF), other stock above the pharmacy's limit (STOCK_WRITE_OFF).
   * Returns the approving manager's name when an approval was used.
   */
  Optional<Approver> approveWriteOff(boolean controlled, BigDecimal value, String reference) {
    return approve(
        controlled ? ApprovalActions.CONTROLLED_WRITE_OFF : ApprovalActions.STOCK_WRITE_OFF,
        value,
        reference);
  }

  /** {@link Approvals#require} and, when an approval was used, the manager who gave it. */
  Optional<Approver> approve(String action, BigDecimal value, String reference) {
    BigDecimal amount = Amounts.money(value == null ? BigDecimal.ZERO : value);
    if (!approvals.needed(action, amount)) {
      return Optional.empty();
    }
    return approvals
        .require(action, amount, reference)
        .map(approver -> new Approver(approver.name(), approver.uid()));
  }

  /**
   * As {@link #approve}, once per action in the transaction: the rows of an import are approved
   * together. The mark lives in the transaction's resources and goes with it.
   */
  void requireOnce(String action, BigDecimal value, String reference) {
    String key = ONCE + action;
    if (TransactionSynchronizationManager.hasResource(key)) {
      return;
    }
    approve(action, value, reference);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.bindResource(key, Boolean.TRUE);
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
              TransactionSynchronizationManager.unbindResourceIfPossible(key);
            }
          });
    }
  }

  /** Runs the write-offs of a document whose approval was taken for its total. */
  <T> T covered(Supplier<T> work) {
    Boolean previous = COVERED.get();
    COVERED.set(Boolean.TRUE);
    try {
      return work.get();
    } finally {
      if (previous == null) {
        COVERED.remove();
      } else {
        COVERED.set(previous);
      }
    }
  }

  /** Whether the current write-off is part of a document already approved. */
  boolean isCovered() {
    return Boolean.TRUE.equals(COVERED.get());
  }
}
