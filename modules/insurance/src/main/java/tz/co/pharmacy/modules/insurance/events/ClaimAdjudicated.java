package tz.co.pharmacy.modules.insurance.events;

import java.math.BigDecimal;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.DomainEvent;
import tz.co.pharmacy.platform.kernel.EventMetadata;

/** "insurance.claim_adjudicated". */
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
    String claimNumber)
    implements DomainEvent {}
