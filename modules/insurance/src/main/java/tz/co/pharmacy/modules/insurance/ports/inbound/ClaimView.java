package tz.co.pharmacy.modules.insurance.ports.inbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A claim to a payer for an insured sale. */
public record ClaimView(
    UUID uid,
    String number,
    UUID payerUid,
    String payerName,
    UUID memberUid,
    String memberNumber,
    String patientName,
    UUID saleUid,
    String saleNumber,
    String preauthNumber,
    String diagnosisCode,
    BigDecimal claimed,
    BigDecimal approved,
    BigDecimal paid,
    ClaimStatus status,
    UUID batchUid,
    List<ClaimLineView> lines,
    Instant createdAt,
    UUID customerUid,
    String batchNumber,
    String note) {}
