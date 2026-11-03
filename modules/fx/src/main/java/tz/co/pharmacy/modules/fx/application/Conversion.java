package tz.co.pharmacy.modules.fx.application;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Currency;

/**
 * Pure conversion arithmetic. A rate is kept to 10 decimals. A converted amount is rounded
 * half-even (banker's rounding, so repeated conversions do not drift) to the target currency's ISO
 * 4217 minor units: TZS and USD 2, JPY 0, KWD 3. Display totals round half-up in the clients.
 */
public final class Conversion {

  public static final int RATE_SCALE = 10;
  private static final MathContext PRECISION = new MathContext(24, RoundingMode.HALF_UP);

  private Conversion() {}

  public static int minorUnits(String currency) {
    int digits = Currency.getInstance(currency).getDefaultFractionDigits();
    return Math.max(digits, 0);
  }

  public static BigDecimal convert(BigDecimal amount, BigDecimal rate, String to) {
    return amount.multiply(rate, PRECISION).setScale(minorUnits(to), RoundingMode.HALF_EVEN);
  }

  /**
   * The realised exchange difference of settling {@code foreignAmount} at {@code settlementRate}
   * after it was booked at {@code bookedRate}, in {@code base} (both rates are base units per 1
   * foreign unit). Positive means the settlement cost more base than was booked: for a payable that
   * is a loss, for a receivable a gain.
   */
  public static BigDecimal difference(
      BigDecimal foreignAmount, BigDecimal bookedRate, BigDecimal settlementRate, String base) {
    return convert(foreignAmount, settlementRate, base)
        .subtract(convert(foreignAmount, bookedRate, base));
  }

  /** The rate of the opposite pair: 1 / rate, to {@link #RATE_SCALE} decimals. */
  public static BigDecimal invert(BigDecimal rate) {
    return BigDecimal.ONE.divide(rate, RATE_SCALE, RoundingMode.HALF_UP);
  }

  public static BigDecimal normalise(BigDecimal rate) {
    return rate.setScale(RATE_SCALE, RoundingMode.HALF_UP);
  }
}
