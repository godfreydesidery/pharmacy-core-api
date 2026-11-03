package tz.co.pharmacy.modules.insurance.ports.inbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/** A payer's payment and the claims it settled, by claim number. */
public record RemittanceView(
    UUID uid,
    String number,
    UUID payerUid,
    String payerName,
    BigDecimal amount,
    String reference,
    LocalDate date,
    Map<String, BigDecimal> allocations,
    Instant createdAt) {}
