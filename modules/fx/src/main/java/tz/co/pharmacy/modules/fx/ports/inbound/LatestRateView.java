package tz.co.pharmacy.modules.fx.ports.inbound;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The rate in force for a pair on a day: units of {@code quote} per 1 {@code base}. {@code stale}
 * is true when it took effect more than 7 days before that day; clients warn about it. {@code
 * source} is IDENTITY for a currency against itself and INVERSE when only the opposite pair has a
 * rate.
 */
public record LatestRateView(
    String base,
    String quote,
    BigDecimal rate,
    LocalDate effectiveOn,
    String source,
    boolean stale) {}
