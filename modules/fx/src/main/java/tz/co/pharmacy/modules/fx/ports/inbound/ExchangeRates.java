package tz.co.pharmacy.modules.fx.ports.inbound;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;

/** The current tenant's exchange rates. */
public interface ExchangeRates {

  /**
   * Units of {@code quote} per 1 {@code base}: the latest rate effective on or before {@code on},
   * or the inverse of the latest opposite rate; 1 when the currencies are the same. Refuses with
   * 422 rate_missing (field {@code currency}) when there is none.
   */
  BigDecimal rate(String base, String quote, LocalDate on);

  /** The rate in force with its date, source and whether it is stale (older than 7 days). */
  LatestRateView latest(String base, String quote, LocalDate on);

  /**
   * {@code amount} in {@code from} as {@code to}, rounded half-even to {@code to}'s minor units.
   */
  BigDecimal convert(BigDecimal amount, String from, String to, LocalDate on);

  /**
   * The realised exchange difference, in {@code base}, of settling {@code foreignAmount} at {@code
   * settlementRate} after booking it at {@code bookedRate} (both base units per 1 foreign unit):
   * settled base value minus booked base value. For a payable a positive difference is a loss; for
   * a receivable it is a gain.
   */
  BigDecimal realisedDifference(
      BigDecimal foreignAmount, BigDecimal bookedRate, BigDecimal settlementRate, String base);

  /** The number of decimals amounts in {@code currency} are rounded to (ISO 4217). */
  int minorUnits(String currency);

  /** Newest first; {@code quote} filters by either side of the pair when given. */
  Page<RateView> list(String quote, PageQuery query);

  /** Records (or replaces the same day's) manual rate; source MANUAL. */
  RateView record(RateCommand command);

  /**
   * Pulls today's rates of every accepted currency against the tenant's base from the rate
   * provider, stored as {@code foreign → base}; empty when no provider is configured.
   */
  List<RateView> refresh();
}
