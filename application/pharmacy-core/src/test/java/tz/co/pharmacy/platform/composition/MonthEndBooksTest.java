package tz.co.pharmacy.platform.composition;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tz.co.pharmacy.modules.finance.ports.inbound.ExpiryProvisions;
import tz.co.pharmacy.modules.inventory.ports.inbound.StockQueries;
import tz.co.pharmacy.modules.purchasing.ports.inbound.SupplierDebitNotes;
import tz.co.pharmacy.modules.tenancy.ports.inbound.ProvisionBand;
import tz.co.pharmacy.platform.kernel.TaxCategory;

/** The month-end measures, without a database. */
class MonthEndBooksTest {

  private static final List<ProvisionBand> DEFAULT =
      List.of(
          new ProvisionBand(0, new BigDecimal("100")),
          new ProvisionBand(30, new BigDecimal("50")),
          new ProvisionBand(90, new BigDecimal("25")));

  @Test
  @DisplayName("each batch takes its first band: expired, within 30, within 90, beyond, no expiry")
  void bands() {
    UUID branch = UUID.randomUUID();
    LocalDate end = LocalDate.of(2026, 11, 30);
    List<ExpiryProvisions.ProvisionBranchMeasure> measured =
        MonthEndBooks.measure(
            List.of(
                exposure(branch, end, "100.00"), // expires on the day: expired
                exposure(branch, end.plusDays(30), "200.00"),
                exposure(branch, end.plusDays(31), "400.00"),
                exposure(branch, end.plusDays(90), "800.00"),
                exposure(branch, end.plusDays(91), "1600.00"),
                exposure(branch, null, "3200.00")),
            DEFAULT,
            end);
    ExpiryProvisions.ProvisionBranchMeasure only = measured.getFirst();
    assertThat(only.stockAtCost()).isEqualByComparingTo("6300");
    assertThat(only.bands())
        .extracting(ExpiryProvisions.ProvisionBandMeasure::costAtRisk)
        .usingElementComparator(BigDecimal::compareTo)
        .containsExactly(new BigDecimal("100"), new BigDecimal("200"), new BigDecimal("1200"));
    // 100 + 100 + 300.
    assertThat(only.provision()).isEqualByComparingTo("500");
  }

  @Test
  @DisplayName("the taxable share: rounded up to a whole percent, 100% without exempt sales")
  void ratio() {
    assertThat(MonthEndBooks.ratio(new BigDecimal("1000"), new BigDecimal("3000"), true))
        .isEqualByComparingTo("25");
    assertThat(MonthEndBooks.ratio(new BigDecimal("1001"), new BigDecimal("3000"), true))
        .isEqualByComparingTo("26");
    assertThat(MonthEndBooks.ratio(new BigDecimal("1001"), new BigDecimal("3000"), false))
        .isEqualByComparingTo("25.02");
    assertThat(MonthEndBooks.ratio(new BigDecimal("500"), BigDecimal.ZERO, true))
        .isEqualByComparingTo("100");
    assertThat(MonthEndBooks.ratio(BigDecimal.ZERO, BigDecimal.ZERO, true))
        .isEqualByComparingTo("100");
    assertThat(MonthEndBooks.ratio(BigDecimal.ZERO, new BigDecimal("10"), true))
        .isEqualByComparingTo("0");
  }

  @Test
  @DisplayName("a receipt's VAT follows its lines; an unknown product's share is residual")
  void split() {
    UUID standard = UUID.randomUUID();
    UUID exempt = UUID.randomUUID();
    UUID unknown = UUID.randomUUID();
    Map<UUID, TaxCategory> categories =
        Map.of(standard, TaxCategory.STANDARD, exempt, TaxCategory.EXEMPT);
    BigDecimal[] mixed =
        MonthEndBooks.split(
            vat("100.00", line(standard, "1000"), line(exempt, "1000"), line(unknown, "1000")),
            categories);
    assertThat(mixed[0]).isEqualByComparingTo("33.33");
    assertThat(mixed[1]).isEqualByComparingTo("33.33");
    assertThat(mixed[2]).isEqualByComparingTo("33.34");
    assertThat(MonthEndBooks.split(vat("180.00", line(exempt, "1000")), categories)[1])
        .isEqualByComparingTo("180");
    assertThat(MonthEndBooks.split(vat("90.00", line(standard, "500")), categories)[0])
        .isEqualByComparingTo("90");
    assertThat(MonthEndBooks.split(vat("7.00"), categories)[2]).isEqualByComparingTo("7");
  }

  @Test
  @DisplayName("the jobs measure a pharmacy on its month's first seven days only")
  void dueDays() {
    assertThat(MonthEndSchedule.due(LocalDate.of(2026, 12, 1))).isTrue();
    assertThat(MonthEndSchedule.due(LocalDate.of(2026, 12, 7))).isTrue();
    assertThat(MonthEndSchedule.due(LocalDate.of(2026, 12, 8))).isFalse();
    assertThat(MonthEndSchedule.due(LocalDate.of(2026, 11, 24))).isFalse();
  }

  private static StockQueries.ExpiryExposure exposure(UUID branch, LocalDate expiry, String cost) {
    return new StockQueries.ExpiryExposure(branch, expiry, new BigDecimal(cost));
  }

  private static SupplierDebitNotes.ReceiptLineValue line(UUID product, String value) {
    return new SupplierDebitNotes.ReceiptLineValue(product, new BigDecimal(value));
  }

  private static SupplierDebitNotes.ReceiptInputVat vat(
      String vat, SupplierDebitNotes.ReceiptLineValue... lines) {
    return new SupplierDebitNotes.ReceiptInputVat(
        UUID.randomUUID(), "GRN-1", new BigDecimal(vat), List.of(lines));
  }
}
