package tz.co.pharmacy.delivery.api.v1.reports;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import tz.co.pharmacy.platform.kernel.Refusal;

/** A report's date range: the last 30 days by default, at most 366 days, from not after to. */
record ReportRange(LocalDate from, LocalDate to) {

  static final int MAX_DAYS = 366;

  static ReportRange of(LocalDate from, LocalDate to, LocalDate today) {
    LocalDate end = to == null ? today : to;
    LocalDate start = from == null ? end.minusDays(29) : from;
    if (start.isAfter(end)) {
      throw Refusal.invalid("invalid_range", "from must not be after to.", "from");
    }
    if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_DAYS) {
      throw Refusal.invalid("invalid_range", "A report covers at most 366 days.", "from");
    }
    return new ReportRange(start, end);
  }
}
