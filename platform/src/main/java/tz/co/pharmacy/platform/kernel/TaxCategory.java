package tz.co.pharmacy.platform.kernel;

import java.math.BigDecimal;

/**
 * How VAT treats a supply (adversarial review STD-2), shared by the catalogue (a product's
 * category), sales (each line keeps the category it was sold under), the fiscal port and the VAT
 * report.
 *
 * <ul>
 *   <li>{@link #STANDARD}: taxed at the pharmacy's standard rate (its settings; 18% in Tanzania,
 *       VAT Act 2014 s.5, <b>verify</b> with the pharmacy's accountant).
 *   <li>{@link #ZERO_RATED}: taxed at 0%; the input VAT on it stays recoverable (in Tanzania mainly
 *       exports, VAT Act 2014 s.55 and the Second Schedule, <b>verify</b>).
 *   <li>{@link #EXEMPT}: outside VAT; no VAT is charged and the input VAT on it is not recoverable.
 *       Most medicines and medical supplies are exempt in Tanzania (VAT Act 2014, Schedule, Part I,
 *       <b>verify</b> each product with the accountant).
 * </ul>
 *
 * A product saved with only a rate (older clients, imports) is STANDARD when the rate is above 0
 * and EXEMPT at 0 (see {@link #fromRate}).
 */
public enum TaxCategory {
  STANDARD,
  ZERO_RATED,
  EXEMPT;

  /**
   * The category of a line or product that kept only its rate: above 0 STANDARD, else EXEMPT (the
   * usual case of a medicine in Tanzania; a zero-rated supply names its category).
   */
  public static TaxCategory fromRate(BigDecimal ratePercent) {
    return ratePercent != null && ratePercent.signum() > 0 ? STANDARD : EXEMPT;
  }

  /** The category kept as text, or the one its rate implies when none was kept. */
  public static TaxCategory of(String kept, BigDecimal ratePercent) {
    if (kept != null) {
      try {
        return valueOf(kept);
      } catch (IllegalArgumentException unknown) {
        // fall through to the rate
      }
    }
    return fromRate(ratePercent);
  }

  /** Whether the category is charged VAT at the standard rate. */
  public boolean taxed() {
    return this == STANDARD;
  }
}
