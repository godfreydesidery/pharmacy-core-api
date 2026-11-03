package tz.co.pharmacy.modules.sales.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.function.BiFunction;
import tz.co.pharmacy.modules.sales.ports.inbound.PaymentMethod;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * How a sale's payments cover its total when they may be in several currencies, such as USD 10 cash
 * and the rest in TZS by mobile money. Each payment keeps its own currency and the rate locked at
 * the moment of sale (base units per 1 payment unit); coverage and change are computed in the base
 * currency, and change is always given in base cash. Conversion rounds half-even to the base
 * currency's minor units.
 */
public final class Tender {

  private Tender() {}

  /** One payment, with its value in the base currency. */
  public record Line(
      PaymentMethod method,
      String currency,
      BigDecimal amount,
      BigDecimal exchangeRate,
      BigDecimal baseAmount) {}

  /** {@code paid} and {@code changeDue} are in the base currency. */
  public record Result(List<Line> lines, BigDecimal paid, BigDecimal changeDue) {}

  /**
   * A payment of {@code amount} in {@code currency} at {@code rate}; {@code round} rounds an amount
   * to a currency's minor units.
   */
  public static Line line(
      PaymentMethod method,
      String currency,
      BigDecimal amount,
      BigDecimal rate,
      String base,
      BiFunction<BigDecimal, String, BigDecimal> round) {
    BigDecimal paid = round.apply(amount, currency);
    BigDecimal baseAmount = currency.equals(base) ? paid : round.apply(paid.multiply(rate), base);
    return new Line(
        method, currency, paid, currency.equals(base) ? BigDecimal.ONE : rate, baseAmount);
  }

  /**
   * Checks the payments cover {@code total} and that only cash exceeds it: 422 payment_short, or
   * 422 overpaid_non_cash when the change would be more than the cash tendered.
   */
  public static Result settle(List<Line> lines, BigDecimal total) {
    BigDecimal paid = BigDecimal.ZERO;
    BigDecimal cash = BigDecimal.ZERO;
    for (Line line : lines) {
      paid = paid.add(line.baseAmount());
      if (line.method() == PaymentMethod.CASH) {
        cash = cash.add(line.baseAmount());
      }
    }
    int scale = Math.max(total.scale(), 0);
    paid = paid.setScale(scale, RoundingMode.HALF_EVEN);
    if (paid.compareTo(total) < 0) {
      throw Refusal.invalid(
          "payment_short",
          "The payments cover "
              + paid.toPlainString()
              + " of a total of "
              + total.toPlainString()
              + ".",
          "payments");
    }
    BigDecimal change = paid.subtract(total);
    if (change.compareTo(cash) > 0) {
      throw Refusal.invalid(
          "overpaid_non_cash", "Only a cash payment may exceed the total.", "payments");
    }
    return new Result(List.copyOf(lines), paid, change);
  }
}
