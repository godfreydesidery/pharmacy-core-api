package tz.co.pharmacy.platform.kernel;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Money is a {@link BigDecimal} with two decimals, rounded half-up; never a floating point. */
public final class Amounts {

  public static final BigDecimal ZERO = money(BigDecimal.ZERO);
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private Amounts() {}

  public static BigDecimal money(BigDecimal value) {
    return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
  }

  /** {@code percent} of {@code amount}, such as 18% of 1,000.00 = 180.00. */
  public static BigDecimal percentOf(BigDecimal amount, BigDecimal percent) {
    if (percent == null || percent.signum() == 0) {
      return ZERO;
    }
    return money(amount.multiply(percent).divide(HUNDRED, 6, RoundingMode.HALF_UP));
  }

  public static BigDecimal times(BigDecimal unit, int quantity) {
    return money(unit.multiply(BigDecimal.valueOf(quantity)));
  }

  /** The percentage change from {@code previous} to {@code value}, or null when previous is 0. */
  public static BigDecimal changePercent(BigDecimal value, BigDecimal previous) {
    if (previous == null || previous.signum() == 0) {
      return null;
    }
    return value
        .subtract(previous)
        .multiply(HUNDRED)
        .divide(previous.abs(), 1, RoundingMode.HALF_UP);
  }
}
