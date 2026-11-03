package tz.co.pharmacy.modules.insurance.ports.inbound;

import java.math.BigDecimal;

/** Claims not yet paid, for the dashboard. */
public record ClaimTotals(
    long pendingClaims, BigDecimal pendingAmount, long draftClaims, BigDecimal approvedUnpaid) {}
