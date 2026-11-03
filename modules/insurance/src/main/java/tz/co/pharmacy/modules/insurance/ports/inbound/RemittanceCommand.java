package tz.co.pharmacy.modules.insurance.ports.inbound;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/** A payment from a payer, spread over claims as given or oldest approved first. */
public record RemittanceCommand(
    UUID payerUid,
    BigDecimal amount,
    String reference,
    LocalDate date,
    Map<UUID, BigDecimal> perClaim) {}
