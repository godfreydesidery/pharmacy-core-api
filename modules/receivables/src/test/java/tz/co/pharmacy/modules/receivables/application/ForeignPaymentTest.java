package tz.co.pharmacy.modules.receivables.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tz.co.pharmacy.modules.receivables.application.CustomerCreditImpl.Conversion;

class ForeignPaymentTest {

  @Test
  @DisplayName(
      "scenario 5 (foreign): USD 120.35 at 2,543.187 TZS is credited 306,072.56; the rounding is reported")
  void convertsAndReportsRounding() {
    Conversion conversion =
        CustomerCreditImpl.conversion(new BigDecimal("120.35"), new BigDecimal("2543.187"));

    // 120.35 x 2543.187 = 306072.555450
    assertThat(conversion.base()).isEqualByComparingTo("306072.56");
    assertThat(conversion.rate()).isEqualByComparingTo("2543.187");
    assertThat(conversion.difference()).isEqualByComparingTo("-0.004550");
    assertThat(conversion.base().add(conversion.difference()))
        .isEqualByComparingTo(new BigDecimal("120.35").multiply(new BigDecimal("2543.187")));
  }

  @Test
  @DisplayName("a whole conversion leaves no difference")
  void exact() {
    Conversion conversion =
        CustomerCreditImpl.conversion(new BigDecimal("100"), new BigDecimal("2500"));
    assertThat(conversion.base()).isEqualByComparingTo("250000.00");
    assertThat(conversion.difference()).isEqualByComparingTo("0");
  }
}
