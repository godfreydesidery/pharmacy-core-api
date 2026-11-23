package tz.co.pharmacy.delivery.api.v1.reports;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * A report's date range: the last 30 days by default, from not after to (422 invalid_range), and at
 * most 366 days, a full year in a leap year (422 period_too_long, PERF-18). A report over a longer
 * time is asked for a year at a time.
 */
record ReportRange(LocalDate from, LocalDate to) {

  static final int MAX_DAYS = 366;

  static ReportRange of(LocalDate from, LocalDate to, LocalDate today) {
    LocalDate end = to == null ? today : to;
    LocalDate start = from == null ? end.minusDays(29) : from;
    if (start.isAfter(end)) {
      throw Refusal.invalid("invalid_range", "from must not be after to.", "from");
    }
    return limited(start, end);
  }

  /** The range as given, refused (422 period_too_long) when longer than 366 days. */
  static ReportRange limited(LocalDate from, LocalDate to) {
    if (from != null && to != null && ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
      throw Refusal.invalid(
          "period_too_long", "A report covers at most 366 days; ask for a year at a time.", "from");
    }
    return new ReportRange(from, to);
  }
}
