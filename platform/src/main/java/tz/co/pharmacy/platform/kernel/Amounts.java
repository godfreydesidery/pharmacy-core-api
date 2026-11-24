package tz.co.pharmacy.platform.kernel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Locale;
import java.util.Objects;
import java.util.function.IntSupplier;

/**
 * Money is a {@link BigDecimal}, never a floating point, rounded to its currency's ISO 4217 minor
 * units: 0 for UGX, RWF and JPY, 2 for TZS, KES and USD, 3 for KWD (the JDK's ISO 4217 table).
 *
 * <p><b>One rounding mode, {@link #ROUNDING} = half-up</b> (a tie goes away from zero: 0.005 →
 * 0.01, -0.005 → -0.01), for every amount: line totals, tax, discounts, conversions between
 * currencies and postings. It is the rule of tax receipts (TRA, KRA, URA) and of currency
 * conversion in Council Regulation (EC) 1103/97 art. 5, and it is symmetric, so a refund of the
 * same lines is exactly the negative of the sale. Banker's rounding (half-even) is not used: each
 * amount here is converted once, from its own figure, so there is no repeated rounding for it to
 * even out, and one rule for every amount keeps the till, the receipt and the books the same. Rates
 * and intermediate ratios keep 6 to 10 decimals and are rounded only into an amount.
 *
 * <p>{@link #money(BigDecimal)} rounds to the minor units of the <b>pharmacy's base currency</b>
 * (the tenant the current work runs for, see {@link #useScaleSource}); outside a pharmacy, and in
 * plain unit tests, that is 2. An amount in another currency (a purchase in USD, a foreign tender)
 * goes through {@link #money(BigDecimal, String)} with its own currency. Database columns stay
 * {@code numeric(14,2)} or wider: an amount of a 0-decimal currency is whole and is stored as
 * {@code 1500.00}. A base currency of more than 2 minor units is refused at registration ({@link
 * #requireStorable}), because those columns could not hold it.
 */
public final class Amounts {

  /** The one rounding mode of money (see the class comment). */
  public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

  /** The scale outside a pharmacy, and the most minor units a base currency may have. */
  public static final int DEFAULT_SCALE = 2;

  public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(DEFAULT_SCALE);
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private static volatile IntSupplier scaleSource = () -> DEFAULT_SCALE;

  private Amounts() {}

  /**
   * Installs where the current pharmacy's minor units come from (the application does it at start,
   * reading the tenant's base currency once per transaction). Returns the source.
   */
  public static <S extends IntSupplier> S useScaleSource(S source) {
    scaleSource = Objects.requireNonNull(source, "source");
    return source;
  }

  /** The minor units of the current pharmacy's base currency: 0 for UGX, 2 for TZS. */
  public static int scale() {
    return scaleSource.getAsInt();
  }

  /** An ISO 4217 currency's minor units (2 for a code the JDK does not know or has none for). */
  public static int minorUnits(String currency) {
    if (currency == null || currency.isBlank()) {
      return DEFAULT_SCALE;
    }
    try {
      int digits =
          Currency.getInstance(currency.trim().toUpperCase(Locale.ROOT)).getDefaultFractionDigits();
      return digits < 0 ? DEFAULT_SCALE : digits;
    } catch (IllegalArgumentException unknown) {
      return DEFAULT_SCALE;
    }
  }

  /**
   * 422 currency_not_supported for a base currency of more than {@link #DEFAULT_SCALE} minor units
   * (KWD, BHD, OMR): the money columns keep 2 decimals.
   */
  public static String requireStorable(String currency, String field) {
    if (minorUnits(currency) > DEFAULT_SCALE) {
      throw Refusal.invalid(
              "currency_not_supported",
              currency
                  + " has "
                  + minorUnits(currency)
                  + " decimals; a pharmacy's base currency may have at most "
                  + DEFAULT_SCALE
                  + ".",
              field)
          .with("currency", currency);
    }
    return currency;
  }

  /** An amount in the pharmacy's base currency, rounded to its minor units. */
  public static BigDecimal money(BigDecimal value) {
    return round(value, scale());
  }

  /**
   * An amount in {@code currency}, rounded to that currency's minor units; the base currency's when
   * {@code currency} is null.
   */
  public static BigDecimal money(BigDecimal value, String currency) {
    return round(value, currency == null ? scale() : minorUnits(currency));
  }

  private static BigDecimal round(BigDecimal value, int scale) {
    return (value == null ? BigDecimal.ZERO : value).setScale(scale, ROUNDING);
  }

  /** {@code percent} of {@code amount}, such as 18% of 1,000.00 = 180.00. */
  public static BigDecimal percentOf(BigDecimal amount, BigDecimal percent) {
    if (percent == null || percent.signum() == 0) {
      return money(BigDecimal.ZERO);
    }
    return money(amount.multiply(percent).divide(HUNDRED, 6, ROUNDING));
  }

  public static BigDecimal times(BigDecimal unit, int quantity) {
    return money(unit.multiply(BigDecimal.valueOf(quantity)));
  }

  /** {@link #times} for an amount in {@code currency}. */
  public static BigDecimal times(BigDecimal unit, int quantity, String currency) {
    return money(unit.multiply(BigDecimal.valueOf(quantity)), currency);
  }

  /** The percentage change from {@code previous} to {@code value}, or null when previous is 0. */
  public static BigDecimal changePercent(BigDecimal value, BigDecimal previous) {
    if (previous == null || previous.signum() == 0) {
      return null;
    }
    return value.subtract(previous).multiply(HUNDRED).divide(previous.abs(), 1, ROUNDING);
  }

  /**
   * An amount for people to read in a message, to the currency's minor units: "TZS 240,000.00",
   * "UGX 240,000" (no code when it is blank, then the base currency's minor units).
   */
  public static String display(String currency, BigDecimal amount) {
    boolean blank = currency == null || currency.isBlank();
    BigDecimal value = money(amount, blank ? null : currency);
    String figure = String.format(Locale.ENGLISH, "%,." + value.scale() + "f", value);
    return blank ? figure : currency + " " + figure;
  }
}
