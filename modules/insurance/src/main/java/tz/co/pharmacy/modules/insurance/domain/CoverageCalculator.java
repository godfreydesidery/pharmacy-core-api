package tz.co.pharmacy.modules.insurance.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import tz.co.pharmacy.modules.insurance.ports.inbound.CopayType;
import tz.co.pharmacy.modules.insurance.ports.inbound.CoverageLine;
import tz.co.pharmacy.modules.insurance.ports.inbound.CoverageLineQuote;
import tz.co.pharmacy.modules.insurance.ports.inbound.CoverageQuote;
import tz.co.pharmacy.platform.kernel.Amounts;

/**
 * The split of a basket between insurer and patient. Pure, so it is tested without a database:
 *
 * <ol>
 *   <li>a line whose product or product type the scheme excludes is not covered at all;
 *   <li>the co-pay comes off the eligible lines: a percent of each, or a fixed amount per claim
 *       spread over them in proportion;
 *   <li>the insurer's share is capped at what is left of the member's annual limit; the excess
 *       moves to the patient as not covered.
 * </ol>
 */
public final class CoverageCalculator {

  private CoverageCalculator() {}

  public static CoverageQuote quote(
      UUID memberUid,
      CopayType copayType,
      BigDecimal copayValue,
      BigDecimal annualLimit,
      BigDecimal usedThisYear,
      Set<String> excludedTypes,
      Set<UUID> excludedProducts,
      List<CoverageLine> lines) {
    List<String> notes = new ArrayList<>();
    int n = lines.size();
    BigDecimal[] total = new BigDecimal[n];
    BigDecimal[] eligible = new BigDecimal[n];
    boolean[] excluded = new boolean[n];
    BigDecimal eligibleSum = BigDecimal.ZERO;
    for (int i = 0; i < n; i++) {
      CoverageLine line = lines.get(i);
      total[i] = Amounts.money(line.lineTotal());
      excluded[i] =
          excludedProducts.contains(line.productUid())
              || (line.productType() != null && excludedTypes.contains(line.productType()));
      eligible[i] = excluded[i] ? Amounts.ZERO : total[i];
      eligibleSum = eligibleSum.add(eligible[i]);
    }
    if (n > 0 && eligibleSum.signum() == 0) {
      notes.add("Nothing in the basket is covered by this scheme.");
    }

    BigDecimal[] copay = new BigDecimal[n];
    BigDecimal type = copayValue == null ? BigDecimal.ZERO : copayValue;
    switch (copayType == null ? CopayType.NONE : copayType) {
      case PERCENT -> {
        for (int i = 0; i < n; i++) {
          copay[i] = Amounts.percentOf(eligible[i], type);
        }
      }
      case FIXED -> spread(copay, eligible, eligibleSum, Amounts.money(type.min(eligibleSum)));
      case NONE -> {
        for (int i = 0; i < n; i++) {
          copay[i] = Amounts.ZERO;
        }
      }
    }

    BigDecimal[] covered = new BigDecimal[n];
    BigDecimal coveredSum = BigDecimal.ZERO;
    for (int i = 0; i < n; i++) {
      covered[i] = Amounts.money(eligible[i].subtract(copay[i]));
      coveredSum = coveredSum.add(covered[i]);
    }

    BigDecimal[] overLimit = new BigDecimal[n];
    for (int i = 0; i < n; i++) {
      overLimit[i] = Amounts.ZERO;
    }
    if (annualLimit != null && annualLimit.signum() > 0) {
      BigDecimal remaining =
          Amounts.money(
              annualLimit
                  .subtract(usedThisYear == null ? BigDecimal.ZERO : usedThisYear)
                  .max(BigDecimal.ZERO));
      if (coveredSum.compareTo(remaining) > 0) {
        BigDecimal excess = Amounts.money(coveredSum.subtract(remaining));
        spread(overLimit, covered, coveredSum, excess);
        notes.add(
            "The annual limit leaves "
                + remaining.toPlainString()
                + " this year; "
                + excess.toPlainString()
                + " moves to the patient.");
        coveredSum = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
          covered[i] = Amounts.money(covered[i].subtract(overLimit[i]));
          coveredSum = coveredSum.add(covered[i]);
        }
      }
    }

    List<CoverageLineQuote> quotes = new ArrayList<>();
    BigDecimal copaySum = BigDecimal.ZERO;
    BigDecimal notCoveredSum = BigDecimal.ZERO;
    for (int i = 0; i < n; i++) {
      BigDecimal notCovered = Amounts.money(total[i].subtract(eligible[i]).add(overLimit[i]));
      copaySum = copaySum.add(copay[i]);
      notCoveredSum = notCoveredSum.add(notCovered);
      quotes.add(
          new CoverageLineQuote(
              lines.get(i).productUid(), total[i], covered[i], copay[i], notCovered, excluded[i]));
    }
    for (int i = 0; i < n; i++) {
      if (excluded[i]) {
        notes.add("One or more items are not covered by the scheme.");
        break;
      }
    }
    return new CoverageQuote(
        memberUid,
        Amounts.money(coveredSum),
        Amounts.money(copaySum),
        Amounts.money(notCoveredSum),
        List.copyOf(notes),
        List.copyOf(quotes),
        Amounts.money(copaySum.add(notCoveredSum)));
  }

  /**
   * Spreads {@code amount} over the lines in proportion to {@code base}; the last takes the
   * rounding.
   */
  private static void spread(
      BigDecimal[] into, BigDecimal[] base, BigDecimal baseSum, BigDecimal amount) {
    int last = -1;
    for (int i = 0; i < base.length; i++) {
      into[i] = Amounts.ZERO;
      if (base[i].signum() > 0) {
        last = i;
      }
    }
    if (last < 0 || amount.signum() == 0) {
      return;
    }
    BigDecimal given = BigDecimal.ZERO;
    for (int i = 0; i < base.length; i++) {
      if (base[i].signum() <= 0) {
        continue;
      }
      if (i == last) {
        into[i] = Amounts.money(amount.subtract(given));
      } else {
        into[i] = Amounts.money(amount.multiply(base[i]).divide(baseSum, 6, RoundingMode.HALF_UP));
        given = given.add(into[i]);
      }
    }
  }
}
