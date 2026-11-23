package tz.co.pharmacy.application.checkout.internal;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import tz.co.pharmacy.application.checkout.ports.inbound.CheckoutCommand;
import tz.co.pharmacy.application.checkout.ports.inbound.CheckoutLine;
import tz.co.pharmacy.modules.sales.ports.inbound.PaymentCommand;
import tz.co.pharmacy.modules.sales.ports.inbound.PrescriptionDetails;

/**
 * The fingerprint of a checkout request (WEB-2): SHA-256, in hex, of its canonical form, the way
 * the {@code Idempotency-Key} draft compares a retry with the request that first used the key. The
 * canonical form is the parsed request with its idempotency key left out: every field in a fixed
 * order, text trimmed, a currency in upper case, an amount without trailing zeros ({@code 1600} and
 * {@code 1600.00} are the same), an absent flag as false. So a till that sends the same sale again
 * (the same cart, the same payments) matches however its JSON is spaced, and any change to what is
 * sold or how it is paid does not.
 */
final class SaleFingerprint {

  private SaleFingerprint() {}

  static String of(CheckoutCommand command) {
    StringBuilder canonical = new StringBuilder("checkout/1");
    field(canonical, "customer", command.customerUid());
    field(canonical, "discount", amount(command.discountPercent()));
    List<CheckoutLine> lines = command.lines() == null ? List.of() : command.lines();
    field(canonical, "lines", lines.size());
    for (CheckoutLine line : lines) {
      field(canonical, "product", line.productUid());
      field(canonical, "quantity", line.quantity());
      List<String> serials = line.serialNumbers() == null ? List.of() : line.serialNumbers();
      field(canonical, "serials", serials.size());
      serials.forEach(serial -> field(canonical, "serial", text(serial)));
    }
    List<PaymentCommand> payments = command.payments() == null ? List.of() : command.payments();
    field(canonical, "payments", payments.size());
    for (PaymentCommand payment : payments) {
      if (payment == null) {
        field(canonical, "payment", null);
        continue;
      }
      field(canonical, "method", payment.method());
      field(canonical, "amount", amount(payment.amount()));
      field(canonical, "reference", text(payment.reference()));
      field(canonical, "currency", upper(payment.currency()));
      field(canonical, "rate", amount(payment.exchangeRate()));
      field(canonical, "channel", upper(payment.channel()));
    }
    PrescriptionDetails rx = command.prescription();
    field(canonical, "prescriber", rx == null ? null : text(rx.prescriberName()));
    field(canonical, "registration", rx == null ? null : text(rx.prescriberRegistration()));
    field(canonical, "prescription", rx == null ? null : text(rx.reference()));
    field(canonical, "patient", rx == null ? null : text(rx.patientName()));
    field(canonical, "member", command.memberUid());
    field(canonical, "preauth", text(command.preauthNumber()));
    field(canonical, "diagnosis", text(command.diagnosisCode()));
    field(canonical, "balanceToAccount", Boolean.TRUE.equals(command.balanceToAccount()));
    field(canonical, "excessToAccount", Boolean.TRUE.equals(command.excessToAccount()));
    field(canonical, "plan", upper(command.pricePlan()));
    return sha256(canonical.toString());
  }

  /**
   * One field as {@code name=length:value}, so no value can run into the next (a reference holding
   * a separator cannot pass for two fields); null as {@code name=-}.
   */
  private static void field(StringBuilder out, String name, Object value) {
    out.append('\n').append(name).append('=');
    if (value == null) {
      out.append('-');
    } else {
      String text = value.toString();
      out.append(text.length()).append(':').append(text);
    }
  }

  private static String amount(BigDecimal value) {
    return value == null
        ? null
        : value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
  }

  private static String text(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String upper(String value) {
    String text = text(value);
    return text == null ? null : text.toUpperCase(Locale.ROOT);
  }

  private static String sha256(String canonical) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
