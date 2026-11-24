package tz.co.pharmacy.modules.finance.ports.inbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Partial-exemption input VAT (STD-2, owner's decision of 24 Nov 2026; the accountant confirms the
 * method). Input VAT on purchases for exempt goods only is not reclaimable, for taxable goods only
 * fully reclaimable; the rest (mixed receipts' share and every expense) is split by the taxable
 * share of sales, or by a fixed percentage. The non-reclaimable part moves from 1150 to 5150.
 */
public interface VatApportionments {

  String MONTHLY = "MONTHLY";
  String ANNUAL = "ANNUAL";

  /**
   * What a run measured. {@code ratioPercent} is the reclaimable share of the residual input VAT.
   * For an ANNUAL run every figure covers the financial year that ends with {@code period}.
   */
  record Measure(
      YearMonth period,
      String kind,
      String method,
      BigDecimal ratioPercent,
      boolean roundedUp,
      BigDecimal taxableSales,
      BigDecimal exemptSales,
      BigDecimal directTaxableVat,
      BigDecimal directExemptVat,
      BigDecimal residualVat) {}

  /**
   * A recorded run. {@code nonReclaimable}: direct exempt VAT plus the residual's non-reclaimable
   * share. {@code adjustment}: what was posted to 5150 (for ANNUAL, the year's non-reclaimable less
   * what the monthly runs posted).
   */
  record Run(
      UUID uid,
      String period,
      String kind,
      String method,
      BigDecimal ratioPercent,
      boolean roundedUp,
      BigDecimal taxableSales,
      BigDecimal exemptSales,
      BigDecimal directTaxableVat,
      BigDecimal directExemptVat,
      BigDecimal residualVat,
      BigDecimal nonReclaimable,
      BigDecimal adjustment,
      String journalNumber,
      Instant createdAt) {}

  Optional<Run> find(YearMonth period, String kind);

  /** Records and posts the run once per month and kind, booked on {@code bookOn}. */
  Run record(Measure measure, LocalDate bookOn);

  /** Input VAT on expenses dated in [from, to] (all of it residual: overheads serve both). */
  BigDecimal expenseInputVat(LocalDate from, LocalDate to);

  /** The runs whose month falls in [from, to], newest first. */
  List<Run> runs(LocalDate from, LocalDate to);
}
