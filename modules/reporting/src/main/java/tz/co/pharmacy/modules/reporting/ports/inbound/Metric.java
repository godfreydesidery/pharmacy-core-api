package tz.co.pharmacy.modules.reporting.ports.inbound;

import java.math.BigDecimal;

/** A figure for the period, the same figure for the period before, and the change in percent. */
public record Metric(BigDecimal value, BigDecimal previous, BigDecimal changePercent) {}
