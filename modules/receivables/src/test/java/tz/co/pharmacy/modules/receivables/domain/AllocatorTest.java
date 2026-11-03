package tz.co.pharmacy.modules.receivables.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tz.co.pharmacy.modules.receivables.ports.inbound.EntryType;

class AllocatorTest {

  static final UUID CUSTOMER = UUID.randomUUID();
  static final LocalDate DAY = LocalDate.of(2026, 11, 3);

  static Entry charge(String number, String amount, int daysAgo) {
    return Entry.debit(
        CUSTOMER,
        EntryType.CHARGE,
        number,
        UUID.randomUUID(),
        new BigDecimal(amount),
        true,
        DAY.minusDays(daysAgo),
        DAY.plusDays(30 - daysAgo),
        null);
  }

  static Entry payment(String amount) {
    return Entry.credit(
        CUSTOMER, EntryType.PAYMENT, "RCP-1", null, new BigDecimal(amount), DAY, null);
  }

  @Test
  @DisplayName("scenario 5: one payment clears the oldest invoices first and part of the next")
  void fifo() {
    Entry first = charge("INV-1", "3000.00", 40);
    Entry second = charge("INV-2", "5000.00", 20);
    Entry third = charge("INV-3", "2000.00", 5);
    Entry paid = payment("6500.00");

    List<Allocation> made = Allocator.allocate(paid, List.of(first, second, third));

    assertThat(made).extracting(a -> a.getDebit().getReference()).containsExactly("INV-1", "INV-2");
    assertThat(made)
        .extracting(Allocation::getAmount)
        .containsExactly(new BigDecimal("3000.00"), new BigDecimal("3500.00"));
    assertThat(first.getOpenAmount()).isEqualByComparingTo("0");
    assertThat(second.getOpenAmount()).isEqualByComparingTo("1500");
    assertThat(third.getOpenAmount()).isEqualByComparingTo("2000");
    assertThat(paid.getOpenAmount()).isEqualByComparingTo("0");
    assertThat(paid.getAmount()).isEqualByComparingTo("-6500");
  }

  @Test
  @DisplayName(
      "scenario 6: an overpayment, or a payment before any invoice, stays as account credit")
  void deposit() {
    Entry only = charge("INV-1", "1200.00", 3);
    Entry paid = payment("2000.00");

    Allocator.allocate(paid, List.of(only));
    assertThat(paid.getOpenAmount()).isEqualByComparingTo("800");

    Entry advance = payment("5000.00");
    assertThat(Allocator.allocate(advance, List.of())).isEmpty();
    assertThat(advance.getOpenAmount()).isEqualByComparingTo("5000");

    // Spent later at the POS as ACCOUNT_CREDIT: the oldest credit goes first.
    Entry spend =
        Entry.debit(
            CUSTOMER,
            EntryType.CREDIT_APPLIED,
            "INV-9",
            null,
            new BigDecimal("1500.00"),
            true,
            DAY,
            null,
            null);
    List<Allocation> used = Allocator.settle(spend, List.of(paid, advance));
    assertThat(used)
        .extracting(Allocation::getAmount)
        .containsExactly(new BigDecimal("800.00"), new BigDecimal("700.00"));
    assertThat(spend.getOpenAmount()).isEqualByComparingTo("0");
    assertThat(advance.getOpenAmount()).isEqualByComparingTo("4300");
  }

  @Test
  @DisplayName(
      "scenario 8: a return's credit note settles the sale's own open charge, the rest stays as credit")
  void creditNoteOnReturn() {
    Entry sale = charge("INV-7", "4000.00", 2);
    Entry partlyPaid = payment("2500.00");
    Allocator.allocate(partlyPaid, List.of(sale));
    assertThat(sale.getOpenAmount()).isEqualByComparingTo("1500");

    Entry note =
        Entry.credit(
            CUSTOMER, EntryType.CREDIT_NOTE, "SRN-1", null, new BigDecimal("2000.00"), DAY, null);
    Allocator.allocate(note, List.of(sale));

    assertThat(sale.getOpenAmount()).isEqualByComparingTo("0");
    assertThat(note.getOpenAmount()).isEqualByComparingTo("500");
  }
}
