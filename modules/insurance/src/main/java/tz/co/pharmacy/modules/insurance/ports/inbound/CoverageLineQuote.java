package tz.co.pharmacy.modules.insurance.ports.inbound;

import java.math.BigDecimal;
import java.util.UUID;

/** One line of a quote: the insurer's share, the co-pay and what the scheme does not cover. */
public record CoverageLineQuote(
    UUID productUid,
    BigDecimal lineTotal,
    BigDecimal covered,
    BigDecimal copay,
    BigDecimal notCovered,
    boolean excluded) {}
