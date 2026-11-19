package tz.co.pharmacy.modules.sales.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SaleTotalsTest {

  @Test
  @DisplayName("a line: gross, then the discount, then tax on what is left")
  void lineAmounts() {
    SaleTotals.LineAmounts line =
        SaleTotals.line(new BigDecimal("1500.00"), 3, new BigDecimal("10"), new BigDecimal("18"));

    assertThat(line.gross()).isEqualByComparingTo("4500.00");
    assertThat(line.discount()).isEqualByComparingTo("450.00");
    assertThat(line.tax()).isEqualByComparingTo("729.00");
    assertThat(line.lineTotal()).isEqualByComparingTo("4779.00");
  }

  @Test
  @DisplayName("no discount and no tax leaves the gross")
  void plainLine() {
    SaleTotals.LineAmounts line =
        SaleTotals.line(new BigDecimal("0.80"), 25, BigDecimal.ZERO, BigDecimal.ZERO);

    assertThat(line.lineTotal()).isEqualByComparingTo("20.00");
    assertThat(line.discount()).isEqualByComparingTo("0.00");
    assertThat(line.tax()).isEqualByComparingTo("0.00");
  }

  @Test
  @DisplayName("the sale's totals are the sums of its lines")
  void totals() {
    SaleTotals.Totals totals =
        SaleTotals.of(
            List.of(
                SaleTotals.line(new BigDecimal("3.80"), 2, new BigDecimal("5"), BigDecimal.ZERO),
                SaleTotals.line(
                    new BigDecimal("1.50"), 1, new BigDecimal("5"), new BigDecimal("18"))));

    assertThat(totals.subtotal()).isEqualByComparingTo("9.10");
    assertThat(totals.discountTotal()).isEqualByComparingTo("0.46");
    assertThat(totals.taxTotal()).isEqualByComparingTo("0.26");
    assertThat(totals.total()).isEqualByComparingTo("8.90");
  }

  @Test
  @DisplayName("prices that include VAT: the total is the shelf price, the tax is taken out of it")
  void taxIncluded() {
    SaleTotals.LineAmounts line =
        SaleTotals.line(new BigDecimal("3540.00"), 1, BigDecimal.ZERO, new BigDecimal("18"), true);

    assertThat(line.gross()).isEqualByComparingTo("3540.00");
    assertThat(line.tax()).isEqualByComparingTo("540.00");
    assertThat(line.lineTotal()).isEqualByComparingTo("3540.00");
  }

  @Test
  @DisplayName(
      "tax included, with a discount: the tax is inside what is left after it, to the cent")
  void taxIncludedAfterDiscount() {
    SaleTotals.LineAmounts line =
        SaleTotals.line(
            new BigDecimal("3200.00"), 2, new BigDecimal("10"), new BigDecimal("18"), true);

    // 6,400 − 640 = 5,760 paid; VAT inside it = 5,760 × 18 ÷ 118 = 878.64
    assertThat(line.discount()).isEqualByComparingTo("640.00");
    assertThat(line.lineTotal()).isEqualByComparingTo("5760.00");
    assertThat(line.tax()).isEqualByComparingTo("878.64");
  }

  @Test
  @DisplayName("tax included at a zero rate, or on an exempt medicine, changes nothing")
  void taxIncludedZeroRate() {
    SaleTotals.LineAmounts line =
        SaleTotals.line(new BigDecimal("650.00"), 3, BigDecimal.ZERO, BigDecimal.ZERO, true);

    assertThat(line.lineTotal()).isEqualByComparingTo("1950.00");
    assertThat(line.tax()).isEqualByComparingTo("0.00");
  }

  @Test
  @DisplayName("the four-argument line keeps the old rule: tax added on top")
  void defaultRuleUnchanged() {
    assertThat(
            SaleTotals.line(new BigDecimal("3540.00"), 1, BigDecimal.ZERO, new BigDecimal("18"))
                .lineTotal())
        .isEqualByComparingTo("4177.20");
  }

  @Test
  @DisplayName("a refund is the line's share for the units that came back")
  void share() {
    assertThat(SaleTotals.share(new BigDecimal("10.00"), 1, 3)).isEqualByComparingTo("3.33");
    assertThat(SaleTotals.share(new BigDecimal("4779.00"), 3, 3)).isEqualByComparingTo("4779.00");
  }
}
