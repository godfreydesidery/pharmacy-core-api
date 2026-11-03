package tz.co.pharmacy.modules.fx.ports.inbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Units of {@code quote} per 1 {@code base}, effective from {@code effectiveOn}. */
public record RateView(
    UUID uid,
    String base,
    String quote,
    BigDecimal rate,
    LocalDate effectiveOn,
    String source,
    Instant createdAt) {}
