package tz.co.pharmacy.modules.reporting.ports.inbound;

import java.math.BigDecimal;

/**
 * Money owed to the pharmacy, for the dashboard: customer receivables (of which overdue), credit
 * customers hold, and insurance claims not yet paid. Across the tenant; accounts are not per
 * branch.
 */
public record CreditFigures(
    BigDecimal receivables,
    BigDecimal overdue,
    BigDecimal customerCredit,
    long accountsWithBalance,
    long pendingClaims,
    BigDecimal pendingClaimsAmount) {}
