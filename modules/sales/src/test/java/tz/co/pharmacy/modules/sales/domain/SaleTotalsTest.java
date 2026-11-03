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
  @DisplayName("a refund is the line's share for the units that came back")
  void share() {
    assertThat(SaleTotals.share(new BigDecimal("10.00"), 1, 3)).isEqualByComparingTo("3.33");
    assertThat(SaleTotals.share(new BigDecimal("4779.00"), 3, 3)).isEqualByComparingTo("4779.00");
  }
}
