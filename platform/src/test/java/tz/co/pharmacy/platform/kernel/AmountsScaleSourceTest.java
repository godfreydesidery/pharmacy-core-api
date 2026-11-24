package tz.co.pharmacy.platform.kernel;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.Test;

class AmountsScaleSourceTest {

  @Test
  void aReleasedSourceGivesWayToTheOneBeforeIt() {
    IntSupplier first = () -> 0;
    IntSupplier second = () -> 1;
    try {
      assertThat(Amounts.scale()).isEqualTo(Amounts.DEFAULT_SCALE);
      Amounts.useScaleSource(first);
      Amounts.useScaleSource(second);
      assertThat(Amounts.money(new BigDecimal("12.345"))).isEqualByComparingTo("12.3");

      // A second application context closes while the first keeps running.
      Amounts.releaseScaleSource(second);
      assertThat(Amounts.money(new BigDecimal("12.5"))).isEqualByComparingTo("13");

      Amounts.releaseScaleSource(first);
      assertThat(Amounts.scale()).isEqualTo(Amounts.DEFAULT_SCALE);
    } finally {
      Amounts.releaseScaleSource(first);
      Amounts.releaseScaleSource(second);
    }
  }
}
