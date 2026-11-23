package tz.co.pharmacy.modules.sales.ports.outbound;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Issues the legal fiscal receipt of a sale with the tax authority, such as TRA VFD in Tanzania,
 * and returns its reference. The default adapter issues none and returns empty.
 *
 * <p>It is called after the sale has committed, never inside the transaction that takes the sale
 * number and moves the stock (PERF-1): the {@code sale.completed} reaction calls {@code
 * SaleService.fiscalise}, which holds a short lease on the sale while it calls this port. A call
 * that throws leaves the receipt due and the event undelivered, so the outbox delivers it again; an
 * adapter uses {@code saleNumber} as its idempotency key with the authority, so a retry after a
 * call whose answer was lost does not issue a second receipt.
 */
public interface FiscalReceipts {

  Optional<String> issue(String saleNumber, BigDecimal total, BigDecimal taxTotal);

  /** False for the adapter that issues nothing: its sales are never due a receipt. */
  default boolean enabled() {
    return true;
  }
}
