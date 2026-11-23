package tz.co.pharmacy.modules.inventory.ports.inbound;

import java.util.List;
import java.util.UUID;

/** Writes to stock at the current branch ({@code ExecutionContext.requireBranch()}). */
public interface StockService {

  /** Stock in: creates the batch if new and records a movement; 422 batch_expiry_mismatch. */
  void receive(ReceiveStockCommand command);

  /**
   * An opening balance (type OPENING). Free while the branch has had no sale, goods receipt or
   * transfer in; afterwards it needs a manager (403 approval_required, LATE_OPENING_STOCK). Into an
   * existing batch it must come at the batch's unit cost: 422 batch_cost_differs.
   */
  MovementView openingStock(ReceiveStockCommand command);

  /**
   * Takes {@code quantity} earliest expiry first (FEFO), never from an expired batch, and records
   * SALE movements with {@code reference}; 422 insufficient_stock when short.
   */
  List<Allocation> allocate(UUID productUid, int quantity, String reference);

  /**
   * As {@link #allocate(UUID, int, String)}; for a SERIAL product {@code serialNumbers} name the
   * units sold (422 serials_mismatch, serial_not_in_stock), and with none the oldest in stock are
   * taken. Batches without expiry are taken first in, first out.
   */
  List<Allocation> allocate(
      UUID productUid, int quantity, String reference, List<String> serialNumbers);

  /** Puts sold units back on their batch (SALE_RETURN). */
  void restore(UUID batchUid, int quantity, String reference);

  /** As {@link #restore(UUID, int, String)}, naming the units back of a SERIAL product. */
  void restore(UUID batchUid, int quantity, String reference, List<String> serialNumbers);

  /**
   * Sends {@code quantity} of the batch at the current branch back to its supplier (a
   * SUPPLIER_RETURN movement); a quarantined or expired batch may be returned. For a SERIAL product
   * the batch's oldest units in stock leave. 422 insufficient_stock, batch_not_found.
   */
  Allocation returnToSupplier(UUID batchUid, int quantity, String reference);

  /**
   * 422 insufficient_stock if it would go below zero; publishes stock.adjusted. A reduction needs a
   * note (422 note_required) and a manager's approval for a controlled medicine
   * (CONTROLLED_WRITE_OFF) or above the pharmacy's limit at cost (STOCK_WRITE_OFF).
   */
  MovementView adjust(AdjustStockCommand command);

  /** Sends stock from the current branch, FEFO; 422 same_branch. */
  TransferView dispatch(TransferCommand command);

  /** Receives a transfer in full; see {@link #receiveTransfer(UUID, ReceiveTransferCommand)}. */
  default TransferView receiveTransfer(UUID transferUid) {
    return receiveTransfer(transferUid, null);
  }

  /**
   * Receives a transfer at its destination, the current branch: 403 not_destination; 422
   * receiver_is_sender when the receiver dispatched it (a root administrator excepted). Units short
   * are written off at the sending branch at cost (THEFT_LOSS), which needs a note (422
   * note_required) and a manager above the limit (TRANSFER_SHORTFALL) or for a controlled medicine
   * (CONTROLLED_WRITE_OFF).
   */
  TransferView receiveTransfer(UUID transferUid, ReceiveTransferCommand command);

  /**
   * Takes, until the transaction ends, the per-product lock that {@link #allocate} takes, for all
   * the products at once in uid order. A caller about to take several products (a cart) calls it
   * first, so two tills selling [A, B] and [B, A] queue instead of deadlocking.
   */
  void lockProducts(java.util.Collection<UUID> productUids);
}
