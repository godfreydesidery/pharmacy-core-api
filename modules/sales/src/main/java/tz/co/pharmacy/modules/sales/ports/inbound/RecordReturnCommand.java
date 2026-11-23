package tz.co.pharmacy.modules.sales.ports.inbound;

import java.util.List;
import java.util.UUID;

/**
 * Goods of a sale coming back. {@code refundCurrency} null refunds in the base currency; otherwise
 * it names a foreign currency the sale was tendered in, and the refund is paid in it.
 *
 * <p>The refund goes back the way the sale was paid (see {@code SaleReturnView.refunds}). {@code
 * refundMethod} is optional: when given it must be a way the sale was paid other than INSURANCE
 * (CREDIT and ACCOUNT_CREDIT both mean the customer's account), else 422 {@code
 * refund_method_not_allowed}.
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
