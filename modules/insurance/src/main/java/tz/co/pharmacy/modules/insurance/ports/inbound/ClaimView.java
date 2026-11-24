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
    String note) {

  /** The claim without its diagnosis code, for a caller who may not read health details. */
  public ClaimView withoutDiagnosis() {
    if (diagnosisCode == null) {
      return this;
    }
    return new ClaimView(
        uid,
        number,
        payerUid,
        payerName,
        memberUid,
        memberNumber,
        patientName,
        saleUid,
        saleNumber,
        preauthNumber,
        null,
        claimed,
        approved,
        paid,
        status,
        batchUid,
        lines,
        createdAt,
        customerUid,
        batchNumber,
        note);
  }
}
