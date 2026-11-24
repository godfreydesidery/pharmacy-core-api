package tz.co.pharmacy.modules.finance.ports.inbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The monthly expiry write-down (STD-3, owner's decision of 24 Nov 2026; the pharmacy's accountant
 * confirms the method). Stock stays at its FEFO batch cost; a provision in 1210 (a contra-asset)
 * against 5110 holds what stock near or past expiry is expected to lose. Each month is measured
 * again and only the change is posted.
 */
public interface ExpiryProvisions {

  /** A band of the rule as it was used: up to {@code daysToExpiry} days, {@code percent} off. */
  record Band(int daysToExpiry, BigDecimal percent) {}

  /** What one band held at one branch at cost, and its provision. */
  record BandMeasure(
      int daysToExpiry, BigDecimal percent, BigDecimal costAtRisk, BigDecimal provision) {}

  /** One branch's stock at cost and its provision by band. */
  record BranchMeasure(
      UUID branchUid, BigDecimal stockAtCost, BigDecimal provision, List<BandMeasure> bands) {}

  /** One month's run. {@code journalNumber} null when nothing changed (nothing posted). */
  record Run(
      UUID uid,
      String period,
      LocalDate measuredOn,
      boolean enabled,
      List<Band> bands,
      BigDecimal stockAtCost,
      BigDecimal provision,
      BigDecimal previousProvision,
      BigDecimal adjustment,
      String journalNumber,
      List<BranchMeasure> branches,
      Instant createdAt) {}

  Optional<Run> find(YearMonth period);

  /**
   * Records the month's measure and posts the change from the previous run's provision, once per
   * month: a second call for the month returns the first run. Disabled, the provision is 0 and an
   * earlier one is released.
   */
  Run record(
      YearMonth period,
      LocalDate measuredOn,
      boolean enabled,
      List<Band> bands,
      List<BranchMeasure> branches);

  /** The runs whose month falls in [from, to], newest first. */
  List<Run> list(LocalDate from, LocalDate to);
}
