package tz.co.pharmacy.modules.inventory.ports.inbound;

import java.util.List;
import java.util.UUID;

/** Writes to stock at the current branch ({@code ExecutionContext.requireBranch()}). */
public interface StockService {

  /** Stock in: creates the batch if new and records a movement; 422 batch_expiry_mismatch. */
  void receive(ReceiveStockCommand command);

  /** An opening balance (type OPENING). */
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

  /** 422 insufficient_stock if it would go below zero; publishes stock.adjusted. */
  MovementView adjust(AdjustStockCommand command);

  /** Sends stock from the current branch, FEFO; 422 same_branch. */
  TransferView dispatch(TransferCommand command);

  /** Receives a transfer at its destination, the current branch; 403 not_destination. */
  TransferView receiveTransfer(UUID transferUid);
}
