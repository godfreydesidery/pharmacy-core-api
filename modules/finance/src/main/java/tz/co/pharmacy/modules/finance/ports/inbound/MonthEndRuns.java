package tz.co.pharmacy.modules.finance.ports.inbound;

import java.time.YearMonth;
import java.util.List;

/**
 * The month-end runs of the current pharmacy (owner's decisions of 24 Nov 2026), measured from
 * stock, settings, sales and purchases outside finance (implemented where those meet). Each runs
 * once per month: asking again returns what was recorded.
 */
public interface MonthEndRuns {

  /** The month before the pharmacy's current local month: the last one complete. */
  YearMonth lastCompleteMonth();

  /** The expiry write-down of {@code period} (STD-3), under the pharmacy's rule at the time. */
  ExpiryProvisions.Run expiryProvision(YearMonth period);

  /**
   * The VAT apportionment of {@code period} (STD-2): the MONTHLY run, and in the financial year's
   * last month the ANNUAL true-up too (automatic method). Nothing for a pharmacy not registered.
   */
  List<VatApportionments.Run> vatApportionment(YearMonth period);
}
