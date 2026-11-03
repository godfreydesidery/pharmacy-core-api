package tz.co.pharmacy.modules.reporting.ports.inbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A sale ({@code sign} +1) or a sale return (-1) to project. Amounts are positive; the sign says
 * whether they add to the day or come off it. {@code net} is the total less tax.
 */
public record SaleFact(
    UUID eventId,
    Instant occurredAt,
    UUID branchUid,
    BigDecimal gross,
    BigDecimal discount,
    BigDecimal tax,
    BigDecimal net,
    BigDecimal cost,
    List<ProductFact> products,
    Map<String, BigDecimal> payments,
    int sign) {}
