package tz.co.pharmacy.modules.sales.domain;

import java.math.BigDecimal;
import java.util.List;
import tz.co.pharmacy.platform.kernel.Amounts;

/**
 * The arithmetic of a sale, in one place. Per line: gross = unit price × quantity, discount = gross
 * × discount %, tax = (gross − discount) × tax rate % (prices exclude tax), line total = gross −
 * discount + tax. The sale's subtotal, discount, tax and total are the sums of its lines'.
 */
public final class SaleTotals {

  private SaleTotals() {}

  public static LineAmounts line(
      BigDecimal unitPrice, int quantity, BigDecimal discountPercent, BigDecimal taxRatePercent) {
    BigDecimal gross = Amounts.times(unitPrice, quantity);
    BigDecimal discount = Amounts.percentOf(gross, discountPercent);
    BigDecimal tax = Amounts.percentOf(gross.subtract(discount), taxRatePercent);
    BigDecimal lineTotal = Amounts.money(gross.subtract(discount).add(tax));
    return new LineAmounts(gross, discount, tax, lineTotal);
  }

  public static Totals of(List<LineAmounts> lines) {
    BigDecimal subtotal = Amounts.ZERO;
    BigDecimal discount = Amounts.ZERO;
    BigDecimal tax = Amounts.ZERO;
    BigDecimal total = Amounts.ZERO;
    for (LineAmounts line : lines) {
      subtotal = subtotal.add(line.gross());
      discount = discount.add(line.discount());
      tax = tax.add(line.tax());
      total = total.add(line.lineTotal());
    }
    return new Totals(
        Amounts.money(subtotal), Amounts.money(discount), Amounts.money(tax), Amounts.money(total));
  }

  /**
   * The share of a line's amount for {@code units} of its {@code quantity}, such as the refund of 2
   * of 5 units: amount × units ÷ quantity, rounded half-up.
   */
  public static BigDecimal share(BigDecimal amount, int units, int quantity) {
    return Amounts.money(
        amount
            .multiply(BigDecimal.valueOf(units))
            .divide(BigDecimal.valueOf(quantity), 6, java.math.RoundingMode.HALF_UP));
  }

  public record LineAmounts(
      BigDecimal gross, BigDecimal discount, BigDecimal tax, BigDecimal lineTotal) {}

  public record Totals(
      BigDecimal subtotal, BigDecimal discountTotal, BigDecimal taxTotal, BigDecimal total) {}
}
