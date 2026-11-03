package tz.co.pharmacy.modules.insurance.events;

import java.math.BigDecimal;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.DomainEvent;
import tz.co.pharmacy.platform.kernel.EventMetadata;

/** "insurance.claim_reduced". */
public record ClaimReduced(
    EventMetadata metadata,
    UUID claimUid,
    UUID saleUid,
    BigDecimal amount,
    String refundMethod,
    String reference)
    implements DomainEvent {}
