package tz.co.pharmacy.modules.fx.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConversionTest {

  @Test
  @DisplayName("amounts round half-even to the target currency's ISO 4217 minor units")
  void roundsToMinorUnits() {
    // 12.345 USD at 2650.5 TZS/USD = 32720.42250 → TZS has 2 decimals
    assertThat(Conversion.convert(new BigDecimal("12.345"), new BigDecimal("2650.5"), "TZS"))
        .isEqualByComparingTo("32720.42");
    // JPY has no decimals
    assertThat(Conversion.convert(new BigDecimal("10"), new BigDecimal("15.555"), "JPY"))
        .isEqualByComparingTo("156");
    // KWD has 3
    assertThat(Conversion.convert(new BigDecimal("1"), new BigDecimal("0.30715"), "KWD"))
        .isEqualByComparingTo("0.307");
    // half-even: a tie goes to the even neighbour, so conversions do not drift upwards
    assertThat(Conversion.convert(new BigDecimal("0.005"), BigDecimal.ONE, "USD"))
        .isEqualByComparingTo("0.00");
    assertThat(Conversion.convert(new BigDecimal("0.015"), BigDecimal.ONE, "USD"))
        .isEqualByComparingTo("0.02");
  }

  @Test
  @DisplayName("a realised difference is the base value at settlement minus the booked value")
  void realisedDifference() {
    // USD 1,000 booked at 2,600 TZS/USD, paid at 2,650: the payable cost TZS 50,000 more (a loss)
    assertThat(
            Conversion.difference(
                new BigDecimal("1000"), new BigDecimal("2600"), new BigDecimal("2650"), "TZS"))
        .isEqualByComparingTo("50000.00");
    // paid at 2,580: TZS 20,000 less (a gain)
    assertThat(
            Conversion.difference(
                new BigDecimal("1000"), new BigDecimal("2600"), new BigDecimal("2580"), "TZS"))
        .isEqualByComparingTo("-20000.00");
  }

  @Test
  @DisplayName("minor units come from the JDK's ISO 4217 table")
  void minorUnits() {
    assertThat(Conversion.minorUnits("TZS")).isEqualTo(2);
    assertThat(Conversion.minorUnits("USD")).isEqualTo(2);
    assertThat(Conversion.minorUnits("JPY")).isZero();
    assertThat(Conversion.minorUnits("KWD")).isEqualTo(3);
  }

  @Test
  @DisplayName("an inverted rate keeps ten decimals and converts back within rounding")
  void invertsRates() {
    BigDecimal tzsPerUsd = new BigDecimal("2650");
    BigDecimal usdPerTzs = Conversion.invert(tzsPerUsd);
    assertThat(usdPerTzs.scale()).isEqualTo(Conversion.RATE_SCALE);
    assertThat(Conversion.convert(new BigDecimal("265000"), usdPerTzs, "USD"))
        .isEqualByComparingTo("100.00");
  }
}
