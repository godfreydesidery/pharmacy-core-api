package tz.co.pharmacy.modules.reporting.ports.inbound;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One day of sales; on the totals row {@code date} is null. */
public record SalesReportRow(
    LocalDate date,
    long invoices,
    BigDecimal gross,
    BigDecimal discount,
    BigDecimal tax,
    BigDecimal net,
    BigDecimal cost,
    BigDecimal profit) {}
