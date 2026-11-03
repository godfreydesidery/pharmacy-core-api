package tz.co.pharmacy.modules.reporting.ports.inbound;

import java.math.BigDecimal;

public record ExpenseLine(String category, BigDecimal amount) {}
