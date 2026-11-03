package tz.co.pharmacy.modules.fx.ports.inbound;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A manual rate: units of {@code quote} per 1 {@code base}; {@code effectiveOn} defaults to today.
 */
public record RateCommand(String base, String quote, BigDecimal rate, LocalDate effectiveOn) {}
