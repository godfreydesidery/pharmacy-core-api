package tz.co.pharmacy.modules.reporting.ports.inbound;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TrendPoint(LocalDate date, BigDecimal total) {}
