package tz.co.pharmacy.modules.purchasing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tz.co.pharmacy.modules.purchasing.ports.outbound.PurchasingCurrencies;
import tz.co.pharmacy.platform.kernel.Refusal;

class PricedTest {

  /** A TZS tenant accepting USD, whose rate today is 2,650 TZS/USD. */
  private final PurchasingCurrencies tzs =
      new PurchasingCurrencies() {
        @Override
        public String baseCurrency() {
          return "TZS";
        }

        @Override
        public String accepted(String currency) {
          String code = currency.trim().toUpperCase();
          if (!code.equals("TZS") && !code.equals("USD")) {
            throw Refusal.invalid("currency_not_accepted", code + " is not accepted.", "currency");
          }
          return code;
        }

        @Override
        public BigDecimal rateToBase(String currency, LocalDate on) {
          return new BigDecimal("2650");
        }

        @Override
        public BigDecimal toBase(BigDecimal amount, BigDecimal rate) {
          return amount.multiply(rate).setScale(2, RoundingMode.HALF_EVEN);
        }
      };

  @Test
  @DisplayName("a USD order without a rate locks today's rate; the base currency has rate 1")
  void orderLocksTodaysRate() {
    assertThat(Priced.of(tzs, "usd", null, null, null))
        .isEqualTo(new Priced("USD", new BigDecimal("2650")));
    assertThat(Priced.of(tzs, null, new BigDecimal("9"), null, null))
        .isEqualTo(new Priced("TZS", BigDecimal.ONE));
  }

  @Test
  @DisplayName("a partial receipt of a USD order uses the order's locked rate, not today's")
  void partialReceiptUsesTheOrderRate() {
    Priced receipt = Priced.of(tzs, null, null, "USD", new BigDecimal("2600"));
    assertThat(receipt).isEqualTo(new Priced("USD", new BigDecimal("2600")));
    // 40 boxes at USD 2.50 = USD 100 → TZS 260,000 at the order's rate
    assertThat(tzs.toBase(new BigDecimal("100.00"), receipt.rate()))
        .isEqualByComparingTo("260000.00");
    assertThat(PurchasingViews.baseUnitCost(new BigDecimal("2.50"), receipt.rate()))
        .isEqualByComparingTo("6500.0000");
  }

  @Test
  @DisplayName("a receipt may override the order's rate, but not its currency")
  void receiptOverridesRateNotCurrency() {
    assertThat(Priced.of(tzs, "USD", new BigDecimal("2610"), "USD", new BigDecimal("2600")).rate())
        .isEqualByComparingTo("2610");
    assertThatThrownBy(() -> Priced.of(tzs, "TZS", null, "USD", new BigDecimal("2600")))
        .isInstanceOf(Refusal.class)
        .extracting(refusal -> ((Refusal) refusal).reason())
        .isEqualTo("currency_mismatch");
    assertThatThrownBy(() -> Priced.of(tzs, "EUR", null, null, null))
        .extracting(refusal -> ((Refusal) refusal).reason())
        .isEqualTo("currency_not_accepted");
  }
}
