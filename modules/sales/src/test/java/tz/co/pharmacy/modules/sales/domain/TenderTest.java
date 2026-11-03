package tz.co.pharmacy.modules.sales.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.List;
import java.util.function.BiFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tz.co.pharmacy.modules.sales.ports.inbound.PaymentMethod;
import tz.co.pharmacy.platform.kernel.Refusal;

class TenderTest {

  private static final BiFunction<BigDecimal, String, BigDecimal> ROUND =
      (amount, currency) ->
          amount.setScale(
              Currency.getInstance(currency).getDefaultFractionDigits(), RoundingMode.HALF_EVEN);
  private static final BigDecimal USD_RATE = new BigDecimal("2650");

  @Test
  @DisplayName(
      "USD 10 cash and the rest in TZS by mobile money cover the sale in the base currency")
  void mixedCurrencyTender() {
    BigDecimal total = new BigDecimal("40000.00");
    Tender.Line usd =
        Tender.line(PaymentMethod.CASH, "USD", new BigDecimal("10"), USD_RATE, "TZS", ROUND);
    Tender.Line tzs =
        Tender.line(PaymentMethod.MOBILE_MONEY, "TZS", new BigDecimal("13500"), null, "TZS", ROUND);
    assertThat(usd.baseAmount()).isEqualByComparingTo("26500.00");
    assertThat(usd.exchangeRate()).isEqualByComparingTo("2650");
    assertThat(tzs.exchangeRate()).isEqualByComparingTo("1");

    Tender.Result result = Tender.settle(List.of(usd, tzs), total);
    assertThat(result.paid()).isEqualByComparingTo("40000.00");
    assertThat(result.changeDue()).isEqualByComparingTo("0.00");
  }

  @Test
  @DisplayName("change is given in base cash, and only cash may exceed the total")
  void changeInBaseCash() {
    Tender.Line usd =
        Tender.line(PaymentMethod.CASH, "USD", new BigDecimal("20"), USD_RATE, "TZS", ROUND);
    assertThat(Tender.settle(List.of(usd), new BigDecimal("40000.00")).changeDue())
        .isEqualByComparingTo("13000.00");

    Tender.Line card =
        Tender.line(PaymentMethod.CARD, "USD", new BigDecimal("20"), USD_RATE, "TZS", ROUND);
    assertThatThrownBy(() -> Tender.settle(List.of(card), new BigDecimal("40000.00")))
        .extracting(refusal -> ((Refusal) refusal).reason())
        .isEqualTo("overpaid_non_cash");
  }

  @Test
  @DisplayName("foreign tenders round half-even to minor units, and a short tender is refused")
  void roundingAndShortfall() {
    // USD 3.335 rounds (half-even) to 3.34; at 2,650.55 that is 8,852.837 → TZS 8,852.84
    Tender.Line usd =
        Tender.line(
            PaymentMethod.CASH,
            "USD",
            new BigDecimal("3.335"),
            new BigDecimal("2650.55"),
            "TZS",
            ROUND);
    assertThat(usd.amount()).isEqualByComparingTo("3.34");
    assertThat(usd.baseAmount()).isEqualByComparingTo("8852.84");
    assertThatThrownBy(() -> Tender.settle(List.of(usd), new BigDecimal("9000.00")))
        .extracting(refusal -> ((Refusal) refusal).reason())
        .isEqualTo("payment_short");
  }
}
