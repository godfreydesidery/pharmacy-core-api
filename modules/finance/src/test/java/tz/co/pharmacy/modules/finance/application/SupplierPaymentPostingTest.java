package tz.co.pharmacy.modules.finance.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tz.co.pharmacy.modules.finance.ports.inbound.ReceiptPosting;
import tz.co.pharmacy.modules.finance.ports.outbound.PayablesDirectory.BookedReceipt;

/** Foreign-currency payables: booked at the receipt rate, settled at the payment rate. */
class SupplierPaymentPostingTest {

  @Test
  @DisplayName("a USD receipt is booked in base, noting the USD owed on the payable")
  void receiptNotesForeignAmount() {
    List<Postings.Line> lines =
        Postings.receipt(
            new ReceiptPosting(
                UUID.randomUUID(),
                "GRN-HQ-000001",
                null,
                LocalDate.now(),
                new BigDecimal("2600000.00"),
                "USD",
                new BigDecimal("1000.00")));
    assertThat(lines)
        .containsExactly(
            new Postings.Line("1200", new BigDecimal("2600000.00"), new BigDecimal("0.00")),
            new Postings.Line(
                "2000",
                new BigDecimal("0.00"),
                new BigDecimal("2600000.00"),
                "USD",
                new BigDecimal("1000.00")));
  }

  @Test
  @DisplayName("paying at a higher rate than booked realises a loss: Dr 7000")
  void realisedLoss() {
    // USD 1,000 booked at 2,600 (TZS 2,600,000), paid at 2,650 (TZS 2,650,000)
    List<Postings.Line> lines =
        Postings.supplierPayment(
            new BigDecimal("2600000.00"),
            new BigDecimal("2650000.00"),
            "BANK_TRANSFER",
            "USD",
            new BigDecimal("1000.00"));
    assertThat(lines)
        .extracting(Postings.Line::accountCode, Postings.Line::debit, Postings.Line::credit)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                "2000", new BigDecimal("2600000.00"), new BigDecimal("0.00")),
            org.assertj.core.groups.Tuple.tuple(
                "1010", new BigDecimal("0.00"), new BigDecimal("2650000.00")),
            org.assertj.core.groups.Tuple.tuple(
                "7000", new BigDecimal("50000.00"), new BigDecimal("0.00")));
    assertThat(lines.get(0).currency()).isEqualTo("USD");
    assertThat(lines.get(0).foreignAmount()).isEqualByComparingTo("1000.00");
  }

  @Test
  @DisplayName("paying at a lower rate than booked realises a gain: Cr 7000")
  void realisedGain() {
    List<Postings.Line> lines =
        Postings.supplierPayment(
            new BigDecimal("2600000.00"),
            new BigDecimal("2580000.00"),
            "CASH",
            "USD",
            new BigDecimal("1000.00"));
    assertThat(lines)
        .extracting(Postings.Line::accountCode, Postings.Line::debit, Postings.Line::credit)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                "2000", new BigDecimal("2600000.00"), new BigDecimal("0.00")),
            org.assertj.core.groups.Tuple.tuple(
                "1000", new BigDecimal("0.00"), new BigDecimal("2580000.00")),
            org.assertj.core.groups.Tuple.tuple(
                "7000", new BigDecimal("0.00"), new BigDecimal("20000.00")));
  }

  @Test
  @DisplayName("a base-currency payment has no exchange difference")
  void baseCurrencyPayment() {
    List<Postings.Line> lines =
        Postings.supplierPayment(
            new BigDecimal("500000.00"), new BigDecimal("500000.00"), "CASH", null, null);
    assertThat(lines).hasSize(2);
    assertThat(lines).noneMatch(line -> line.accountCode().equals("7000"));
  }

  @Test
  @DisplayName("receipts booked at different rates settle at their total-weighted rate")
  void weightedBookedRate() {
    UUID supplier = UUID.randomUUID();
    BigDecimal rate =
        SupplierPaymentsImpl.bookedRate(
            List.of(
                new BookedReceipt(
                    UUID.randomUUID(),
                    "GRN-1",
                    supplier,
                    "USD",
                    new BigDecimal("2600"),
                    new BigDecimal("300")),
                new BookedReceipt(
                    UUID.randomUUID(),
                    "GRN-2",
                    supplier,
                    "USD",
                    new BigDecimal("2700"),
                    new BigDecimal("100"))));
    // (300 × 2600 + 100 × 2700) / 400 = 2625
    assertThat(rate).isEqualByComparingTo("2625");
  }
}
