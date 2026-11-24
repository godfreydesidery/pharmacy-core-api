package tz.co.pharmacy.modules.sales.ports.inbound;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The sales that did not happen (FRD-20): what a till reports when a cart with lines on it is
 * cancelled or cleared, a parked sale is thrown away, or the drawer is opened with no sale; and the
 * report of them per cashier and day.
 */
public interface TillEvents {

  /** The kinds of event a till reports. */
  enum Kind {
    CART_CANCELLED,
    CART_CLEARED,
    PARKED_DISCARDED,
    NO_SALE
  }

  /**
   * Records an event of the current cashier at the current branch, on the pharmacy's local day;
   * {@code value} is in the base currency. 422 invalid_event_kind, invalid_lines (0 to 1000),
   * invalid_amount.
   */
  TillEventView record(RecordTillEvent command);

  /**
   * Per day, cashier and kind, at the branches the caller may report on: how many events, with how
   * many lines and what value; days [from, to] (local), at most 92. 422 invalid_period,
   * period_too_long.
   */
  List<TillEventSummary> summary(LocalDate from, LocalDate to);

  record RecordTillEvent(String kind, Integer lines, BigDecimal value, String reason) {}

  record TillEventView(
      UUID uid, String kind, int lines, BigDecimal value, LocalDate day, UUID shiftUid) {}

  record TillEventSummary(
      LocalDate day,
      UUID branchUid,
      UUID cashierUid,
      String cashierName,
      String kind,
      long events,
      long lines,
      BigDecimal value) {}
}
