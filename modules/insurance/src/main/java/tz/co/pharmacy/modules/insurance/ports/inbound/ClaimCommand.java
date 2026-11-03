package tz.co.pharmacy.modules.insurance.ports.inbound;

import java.util.List;
import java.util.UUID;

/** A claim raised by checkout for an insured sale. */
public record ClaimCommand(
    UUID memberUid,
    UUID saleUid,
    String saleNumber,
    String patientName,
    String preauthNumber,
    String diagnosisCode,
    List<ClaimLineView> lines) {}
