package tz.co.pharmacy.modules.insurance.ports.inbound;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** How a basket splits between the insurer (covered) and the patient (copay + notCovered). */
public record CoverageQuote(
    UUID memberUid,
    BigDecimal covered,
    BigDecimal copay,
    BigDecimal notCovered,
    List<String> notes,
    List<CoverageLineQuote> lines,
    BigDecimal patientPays) {}
