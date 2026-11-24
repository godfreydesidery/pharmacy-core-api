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
  void aSupplierReturnWaitsForItsCreditNote() {
    // FRD-16: out of stock into 1160 at once; the credit note moves it to the payable.
    List<Postings.Line> pending = Postings.supplierReturn(new BigDecimal("200.00"), true);
    assertThat(amount(pending, "1160", true)).isEqualByComparingTo("200.00");
    assertThat(amount(pending, "1200", false)).isEqualByComparingTo("200.00");
    assertThat(pending).noneMatch(l -> l.accountCode().equals("2000"));
    List<Postings.Line> legacy = Postings.supplierReturn(new BigDecimal("200.00"), false);
    assertThat(amount(legacy, "2000", true)).isEqualByComparingTo("200.00");

    List<Postings.Line> less =
        Postings.supplierCredit(new BigDecimal("200.00"), new BigDecimal("180.00"));
    assertThat(sum(less, true)).isEqualByComparingTo(sum(less, false));
    assertThat(amount(less, "2000", true)).isEqualByComparingTo("180.00");
    assertThat(amount(less, "1160", false)).isEqualByComparingTo("200.00");
    assertThat(amount(less, "5100", true)).isEqualByComparingTo("20.00");
    List<Postings.Line> more =
        Postings.supplierCredit(new BigDecimal("200.00"), new BigDecimal("230.00"));
    assertThat(amount(more, "5100", false)).isEqualByComparingTo("30.00");
    assertThat(sum(more, true)).isEqualByComparingTo(sum(more, false));
  }

  @Test
  void aForeignReceiptPostsItsConversionRounding() {
    // STD-17: payable 17,790.17 (the total converted once), goods 17,790.18 (three lines each
    // converted once): 7000 takes the 0.01 the other way.
    List<Postings.Line> receipt =
        Postings.receipt(
            new ReceiptPosting(
                UUID.randomUUID(),
                "GRN-HQ-000009",
                UUID.randomUUID(),
                LocalDate.now(),
                new BigDecimal("17790.17"),
                "USD",
                new BigDecimal("6.93"),
                BigDecimal.ZERO,
                new BigDecimal("-0.01")));
    assertThat(sum(receipt, true)).isEqualByComparingTo(sum(receipt, false));
    assertThat(amount(receipt, "1200", true)).isEqualByComparingTo("17790.18");
    assertThat(amount(receipt, "2000", false)).isEqualByComparingTo("17790.17");
    assertThat(amount(receipt, "7000", false)).isEqualByComparingTo("0.01");
  }

  @Test
  void inputVatGoesToVatReceivable() {
    // STD-2: goods 10,000 and VAT 1,800 on the invoice, in USD at 2,650 noted on the payable.
    List<Postings.Line> receipt =
        Postings.receipt(
            new ReceiptPosting(
                UUID.randomUUID(),
                "GRN-HQ-000002",
                UUID.randomUUID(),
                LocalDate.now(),
                new BigDecimal("11800.00"),
                "USD",
                new BigDecimal("4.45"),
                new BigDecimal("1800.00")));
    assertThat(sum(receipt, true)).isEqualByComparingTo(sum(receipt, false));
    assertThat(amount(receipt, "1200", true)).isEqualByComparingTo("10000.00");
    assertThat(amount(receipt, "1150", true)).isEqualByComparingTo("1800.00");
    assertThat(amount(receipt, "2000", false)).isEqualByComparingTo("11800.00");
    assertThat(receipt.stream().filter(l -> l.accountCode().equals("2000")).findFirst())
        .hasValueSatisfying(payable -> assertThat(payable.currency()).isEqualTo("USD"));

    List<Postings.Line> power =
        Postings.expense(
            ExpenseCategory.UTILITIES,
            new BigDecimal("11800.00"),
            new BigDecimal("1800.00"),
            PaidFrom.CASH);
    assertThat(sum(power, true)).isEqualByComparingTo(sum(power, false));
    assertThat(amount(power, "6010", true)).isEqualByComparingTo("10000.00");
    assertThat(amount(power, "1150", true)).isEqualByComparingTo("1800.00");
    assertThat(amount(power, "1000", false)).isEqualByComparingTo("11800.00");
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
