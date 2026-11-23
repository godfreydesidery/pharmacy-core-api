package tz.co.pharmacy.modules.insurance.events;

import java.math.BigDecimal;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.DomainEvent;
import tz.co.pharmacy.platform.kernel.EventMetadata;

/**
 * "insurance.claim_adjudicated". {@code rejected} is the claim's whole rejected part after this
 * decision; {@code writeOffChange} and {@code patientChange} are how far this decision moves what
 * the books hold written off (6095) and charged to the patient (1100), either sign: a claim
 * adjudicated again posts only that change. Both are null on events from before they existed, which
 * carry the first adjudication's rejected part only.
 */
public record ClaimAdjudicated(
    EventMetadata metadata,
    UUID claimUid,
    UUID payerUid,
    BigDecimal claimed,
    BigDecimal approved,
    BigDecimal rejected,
    String rejectedTo,
    UUID customerUid,
    UUID saleUid,
    String saleNumber,
    String claimNumber,
    BigDecimal writeOffChange,
    BigDecimal patientChange)
    implements DomainEvent {}
