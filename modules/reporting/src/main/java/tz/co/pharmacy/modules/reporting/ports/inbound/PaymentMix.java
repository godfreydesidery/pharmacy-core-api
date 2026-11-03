package tz.co.pharmacy.modules.reporting.ports.inbound;

import java.math.BigDecimal;

public record PaymentMix(String method, BigDecimal amount, long count) {}
