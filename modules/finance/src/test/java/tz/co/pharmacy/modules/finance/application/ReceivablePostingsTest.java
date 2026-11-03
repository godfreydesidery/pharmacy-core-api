package tz.co.pharmacy.modules.finance.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReceivablePostingsTest {

  @Test
  @DisplayName(
      "a foreign payment on account: Dr bank / Cr receivable, the rounding balanced through 7000")
  void foreignPaymentBalances() {
    // USD 120.35 x 2,543.187 = 306,072.55545 credited 306,072.56: the exact value is 0.00455 lower.
    List<Postings.Line> lines =
        ReceivablePostingsImpl.customerPaymentLines(
            new BigDecimal("306072.56"),
            "CARD",
            new BigDecimal("-0.004550"),
            "USD",
            new BigDecimal("120.35"));
    assertThat(Postings.balanced(lines)).isEqualTo(lines);
    assertThat(lines.getFirst().accountCode()).isEqualTo("1010");
    assertThat(lines.get(1).accountCode()).isEqualTo("1100");
    assertThat(lines.get(1).credit()).isEqualByComparingTo("306072.56");
  }

  @Test
  @DisplayName(
      "a rounding of a cent or more posts to FX gains and losses so the entry still balances")
  void fxDifferenceToSevenThousand() {
    List<Postings.Line> lines =
        ReceivablePostingsImpl.customerPaymentLines(
            new BigDecimal("1000.00"),
            "MOBILE_MONEY",
            new BigDecimal("-0.020000"),
            "KES",
            new BigDecimal("50"));
    Postings.balanced(lines);
    assertThat(lines)
        .anySatisfy(
            line -> {
              assertThat(line.accountCode()).isEqualTo("7000");
              assertThat(line.debit()).isEqualByComparingTo("0.02");
            });
    assertThat(lines.getFirst().debit()).isEqualByComparingTo("999.98");
  }

  @Test
  @DisplayName("account credit spent at the till maps to receivables")
  void accountCreditIsReceivable() {
    assertThat(Postings.paymentAccount("ACCOUNT_CREDIT")).isEqualTo("1100");
  }
}
