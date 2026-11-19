package tz.co.pharmacy.modules.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BatchCostTest {

  static Batch batchAt(String cost) {
    return new Batch(UUID.randomUUID(), "LOT-1", LocalDate.of(2028, 1, 15), new BigDecimal(cost));
  }

  static BigDecimal money(String value) {
    return new BigDecimal(value);
  }

  @Test
  @DisplayName("more of a batch at another cost: the batch costs the weighted average")
  void averages() {
    Batch batch = batchAt("300.00");

    BigDecimal rounding = batch.receive(10, 10, money("310"));

    assertThat(batch.getUnitCost()).isEqualByComparingTo("305.00");
    assertThat(rounding).isEqualByComparingTo("0.00");
  }

  @Test
  @DisplayName("an average between cents is kept to the cent, and the rounding is returned")
  void roundsToTheCent() {
    Batch batch = batchAt("100.00");

    // (3 × 100 + 4 × 101) / 7 = 100.5714… → 100.57; 7 × 100.57 = 703.99 against 704.00 held and
    // paid
    BigDecimal rounding = batch.receive(3, 4, money("101"));

    assertThat(batch.getUnitCost()).isEqualByComparingTo("100.57");
    assertThat(rounding).isEqualByComparingTo("-0.01");
  }

  @Test
  @DisplayName("the same cost, or nothing on hand: no averaging and no rounding")
  void sameCostOrEmpty() {
    Batch same = batchAt("250.00");
    assertThat(same.receive(40, 5, money("250"))).isEqualByComparingTo("0");
    assertThat(same.getUnitCost()).isEqualByComparingTo("250.00");

    Batch empty = batchAt("250.00");
    assertThat(empty.receive(0, 5, money("260"))).isEqualByComparingTo("0");
    assertThat(empty.getUnitCost()).isEqualByComparingTo("260.00");
  }

  @Test
  @DisplayName(
      "whatever the quantities, the stock's value moves by what was paid plus the rounding")
  void valueIsConserved() {
    String[][] cases = {
      {"350.00", "19", "101", "364"}, {"1200", "31", "67", "1248"}, {"0.35", "1000", "7", "0.36"}
    };
    for (String[] c : cases) {
      Batch batch = batchAt(c[0]);
      long onHand = Long.parseLong(c[1]);
      int quantity = Integer.parseInt(c[2]);
      BigDecimal before = batch.getUnitCost().multiply(BigDecimal.valueOf(onHand));
      BigDecimal paid = money(c[3]).multiply(BigDecimal.valueOf(quantity));

      BigDecimal rounding = batch.receive(onHand, quantity, money(c[3]));

      BigDecimal after = batch.getUnitCost().multiply(BigDecimal.valueOf(onHand + quantity));
      assertThat(after).isEqualByComparingTo(before.add(paid).add(rounding));
      assertThat(rounding.abs())
          .isLessThanOrEqualTo(
              new BigDecimal("0.005").multiply(BigDecimal.valueOf(onHand + quantity)));
    }
  }
}
