package tz.co.pharmacy.modules.sales.ports.inbound;

import java.util.List;
import java.util.UUID;

/**
 * Goods of a sale coming back. {@code refundCurrency} null refunds in the base currency; otherwise
 * it names a foreign currency the sale was tendered in, and the refund is paid in it.
 */
public record RecordReturnCommand(
    UUID saleUid,
    String reason,
    PaymentMethod refundMethod,
    List<ReturnLineCommand> lines,
    String refundCurrency) {

  public RecordReturnCommand(
      UUID saleUid, String reason, PaymentMethod refundMethod, List<ReturnLineCommand> lines) {
    this(saleUid, reason, refundMethod, lines, null);
  }
}
