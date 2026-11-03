package tz.co.pharmacy.modules.insurance.ports.inbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** The claims of one payer and period, submitted together. */
public record ClaimBatchView(
    UUID uid,
    String number,
    UUID payerUid,
    String payerName,
    LocalDate periodFrom,
    LocalDate periodTo,
    int claimCount,
    BigDecimal claimed,
    BigDecimal approved,
    BigDecimal paid,
    String status,
    Instant submittedAt) {}
