package tz.co.pharmacy.modules.receivables.ports.inbound;

import java.math.BigDecimal;

/** The tenant's receivables at a glance, for the dashboard. */
public record ReceivableTotals(
    BigDecimal outstanding,
    BigDecimal overdue,
    BigDecimal unallocatedCredit,
    long accountsWithBalance) {}
