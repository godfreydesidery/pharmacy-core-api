package tz.co.pharmacy.application.checkout.ports.inbound;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import tz.co.pharmacy.modules.sales.ports.inbound.PaymentCommand;
import tz.co.pharmacy.modules.sales.ports.inbound.PrescriptionDetails;

/**
 * A POS sale as the till sends it: the cart, the payments and, for prescription items, the
 * prescription. A repeated {@code idempotencyKey} returns the first sale.
 *
 * <p>An insured sale names the {@code memberUid} (with {@code preauthNumber} and {@code
 * diagnosisCode} when the payer asks): the sale then carries an INSURANCE payment of exactly the
 * covered amount, added for the till when it sends none. {@code balanceToAccount} puts whatever the
 * payments leave unpaid on the named customer's account as a CREDIT payment; {@code
 * excessToAccount} keeps a non-cash overpayment on the account as credit instead of refusing it.
 */
public record CheckoutCommand(
    String idempotencyKey,
    UUID customerUid,
    BigDecimal discountPercent,
    List<CheckoutLine> lines,
    List<PaymentCommand> payments,
    PrescriptionDetails prescription,
    UUID memberUid,
    String preauthNumber,
    String diagnosisCode,
    Boolean balanceToAccount,
    Boolean excessToAccount) {

  /** A sale without insurance or account settlement. */
  public CheckoutCommand(
      String idempotencyKey,
      UUID customerUid,
      BigDecimal discountPercent,
      List<CheckoutLine> lines,
      List<PaymentCommand> payments,
      PrescriptionDetails prescription) {
    this(
        idempotencyKey,
        customerUid,
        discountPercent,
        lines,
        payments,
        prescription,
        null,
        null,
        null,
        null,
        null);
  }
}
