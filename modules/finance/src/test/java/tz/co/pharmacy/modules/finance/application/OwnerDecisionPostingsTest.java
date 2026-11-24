package tz.co.pharmacy.modules.finance.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The postings of the owner's decisions of 24 Nov 2026: each balances, on the agreed accounts. */
class OwnerDecisionPostingsTest {

  @Test
  @DisplayName("the expiry provision grows Dr 5110 / Cr 1210 and shrinks the reverse")
  void expiryProvision() {
    List<Postings.Line> grows = Postings.expiryProvision(new BigDecimal("2500.00"));
    assertThat(amount(grows, "5110", true)).isEqualByComparingTo("2500");
    assertThat(amount(grows, "1210", false)).isEqualByComparingTo("2500");
    List<Postings.Line> shrinks = Postings.expiryProvision(new BigDecimal("-600.00"));
    assertThat(amount(shrinks, "1210", true)).isEqualByComparingTo("600");
    assertThat(amount(shrinks, "5110", false)).isEqualByComparingTo("600");
    assertThat(Postings.expiryProvision(BigDecimal.ZERO)).isEmpty();
    assertBalanced(grows);
    assertBalanced(shrinks);
  }

  @Test
  @DisplayName("non-reclaimable input VAT moves from 1150 to 5150; a true-up back the other way")
  void vatApportionment() {
    List<Postings.Line> moved = Postings.vatApportionment(new BigDecimal("260.00"));
    assertThat(amount(moved, "5150", true)).isEqualByComparingTo("260");
    assertThat(amount(moved, "1150", false)).isEqualByComparingTo("260");
    List<Postings.Line> back = Postings.vatApportionment(new BigDecimal("-10.00"));
    assertThat(amount(back, "1150", true)).isEqualByComparingTo("10");
    assertBalanced(moved);
    assertBalanced(back);
  }

  @Test
  @DisplayName("debit notes: a sale's on 1100/4000/2100, a supplier's on 5120/1150/2000")
  void debitNotes() {
    List<Postings.Line> sale =
        Postings.saleDebitNote(new BigDecimal("200.00"), new BigDecimal("36.00"));
    assertThat(amount(sale, "1100", true)).isEqualByComparingTo("236");
    assertThat(amount(sale, "4000", false)).isEqualByComparingTo("200");
    assertThat(amount(sale, "2100", false)).isEqualByComparingTo("36");
    List<Postings.Line> supplier =
        Postings.supplierDebitNote(new BigDecimal("100.00"), new BigDecimal("18.00"));
    assertThat(amount(supplier, "5120", true)).isEqualByComparingTo("100");
    assertThat(amount(supplier, "1150", true)).isEqualByComparingTo("18");
    assertThat(amount(supplier, "2000", false)).isEqualByComparingTo("118");
    // No VAT (not registered, or zero-rated): no VAT line.
    assertThat(Postings.saleDebitNote(new BigDecimal("50.00"), BigDecimal.ZERO))
        .extracting(Postings.Line::accountCode)
        .containsExactly("1100", "4000");
    assertBalanced(sale);
    assertBalanced(supplier);
  }

  @Test
  @DisplayName("every line of the new postings is a debit or a credit, never both")
  void oneSidePerLine() {
    for (List<Postings.Line> lines :
        List.of(
            Postings.expiryProvision(new BigDecimal("-1.00")),
            Postings.vatApportionment(new BigDecimal("1.00")),
            Postings.saleDebitNote(new BigDecimal("1.00"), new BigDecimal("0.18")),
            Postings.supplierDebitNote(new BigDecimal("1.00"), new BigDecimal("0.18")))) {
      assertThat(lines).allMatch(l -> l.debit().signum() == 0 || l.credit().signum() == 0);
      assertThat(lines).allMatch(l -> l.debit().signum() >= 0 && l.credit().signum() >= 0);
    }
  }

  private static void assertBalanced(List<Postings.Line> lines) {
    BigDecimal debits =
        lines.stream().map(Postings.Line::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal credits =
        lines.stream().map(Postings.Line::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(debits).isEqualByComparingTo(credits);
  }

  private static BigDecimal amount(List<Postings.Line> lines, String account, boolean debit) {
    return lines.stream()
        .filter(line -> line.accountCode().equals(account))
        .map(debit ? Postings.Line::debit : Postings.Line::credit)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }
}
