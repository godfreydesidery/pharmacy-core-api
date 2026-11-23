package tz.co.pharmacy.modules.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tz.co.pharmacy.modules.finance.ports.inbound.ExpenseCategory;
import tz.co.pharmacy.modules.finance.ports.inbound.ReceiptPosting;
import tz.co.pharmacy.modules.finance.ports.inbound.ReturnPosting;
import tz.co.pharmacy.modules.finance.ports.inbound.SalePosting;

class PostingsTest {

  @Test
  void aSaleWithChangeAndTwoPaymentMethodsBalances() {
    Map<String, BigDecimal> payments = new LinkedHashMap<>();
    payments.put("CASH", new BigDecimal("10000.00"));
    payments.put("MOBILE_MONEY", new BigDecimal("2000.00"));
    // total 11,800 = net 10,000 + tax 1,800; cash 10,000 less change 200.
    SalePosting sale =
        new SalePosting(
            UUID.randomUUID(),
            "INV-HQ-000001",
            UUID.randomUUID(),
            LocalDate.of(2026, 11, 2),
            new BigDecimal("10000.00"),
            new BigDecimal("1800.00"),
            new BigDecimal("6000.00"),
            new BigDecimal("200.00"),
            payments);

    List<Postings.Line> lines = Postings.sale(sale);

    assertThat(sum(lines, true)).isEqualByComparingTo(sum(lines, false));
    assertThat(amount(lines, "1000", true)).isEqualByComparingTo("9800.00");
    assertThat(amount(lines, "1020", true)).isEqualByComparingTo("2000.00");
    assertThat(amount(lines, "4000", false)).isEqualByComparingTo("10000.00");
    assertThat(amount(lines, "2100", false)).isEqualByComparingTo("1800.00");
    assertThat(amount(lines, "5000", true)).isEqualByComparingTo("6000.00");
    assertThat(amount(lines, "1200", false)).isEqualByComparingTo("6000.00");
  }

  @Test
  void returnsReceiptsAndExpensesBalance() {
    List<Postings.Line> refund =
        Postings.saleReturn(
            new ReturnPosting(
                UUID.randomUUID(),
                "SRN-HQ-000001",
                UUID.randomUUID(),
                LocalDate.now(),
                new BigDecimal("500.00"),
                new BigDecimal("90.00"),
                new BigDecimal("300.00"),
                "CARD"));
    assertThat(sum(refund, true)).isEqualByComparingTo(sum(refund, false));
    assertThat(amount(refund, "1010", false)).isEqualByComparingTo("590.00");

    List<Postings.Line> receipt =
        Postings.receipt(
            new ReceiptPosting(
                UUID.randomUUID(),
                "GRN-HQ-000001",
                UUID.randomUUID(),
                LocalDate.now(),
                new BigDecimal("4200.00")));
    assertThat(amount(receipt, "1200", true)).isEqualByComparingTo("4200.00");
    assertThat(amount(receipt, "2000", false)).isEqualByComparingTo("4200.00");

    List<Postings.Line> rent =
        Postings.expense(ExpenseCategory.RENT, new BigDecimal("1200.00"), PaidFrom.MOBILE_MONEY);
    assertThat(amount(rent, "6000", true)).isEqualByComparingTo("1200.00");
    assertThat(amount(rent, "1020", false)).isEqualByComparingTo("1200.00");
  }

  @Test
  void anUnbalancedEntryIsRefused() {
    assertThatThrownBy(
            () ->
                Postings.balanced(
                    List.of(
                        new Postings.Line("1000", new BigDecimal("10.00"), BigDecimal.ZERO),
                        new Postings.Line("4000", BigDecimal.ZERO, new BigDecimal("9.99")))))
        .isInstanceOf(IllegalStateException.class);
  }

  private static BigDecimal sum(List<Postings.Line> lines, boolean debit) {
    return lines.stream()
        .map(debit ? Postings.Line::debit : Postings.Line::credit)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private static BigDecimal amount(List<Postings.Line> lines, String account, boolean debit) {
    return lines.stream()
        .filter(line -> line.accountCode().equals(account))
        .map(debit ? Postings.Line::debit : Postings.Line::credit)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }
}
