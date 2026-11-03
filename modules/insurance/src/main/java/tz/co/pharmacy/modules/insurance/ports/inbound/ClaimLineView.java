package tz.co.pharmacy.modules.insurance.ports.inbound;

import java.math.BigDecimal;
import java.util.UUID;

/** One item of a claim; a negative amount is a return. */
public record ClaimLineView(
    UUID productUid,
    String productName,
    int quantity,
    BigDecimal amount,
    BigDecimal approvedAmount,
    String rejectionReason) {}
