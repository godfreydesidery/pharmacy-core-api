package tz.co.pharmacy.modules.insurance.events;

import java.math.BigDecimal;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.DomainEvent;
import tz.co.pharmacy.platform.kernel.EventMetadata;

/**
 * "insurance.claim_reduced": a return's insurer share ({@code amount}) came off the claim. It
 * splits by where the claim's value sits in the books: {@code receivableReduction} off what the
 * payer owes (1110), {@code writeOffReversal} off the rejected part written off (6095) and {@code
 * patientReduction} off the rejected part charged to the patient ({@code customerUid}'s account).
 * The three add up to {@code amount}; they are null on events from before they existed, when all of
 * it came off 1110.
 */
public record ClaimReduced(
    EventMetadata metadata,
    UUID claimUid,
    UUID saleUid,
    BigDecimal amount,
    String refundMethod,
    String reference,
    BigDecimal receivableReduction,
    BigDecimal writeOffReversal,
    BigDecimal patientReduction,
    UUID customerUid)
    implements DomainEvent {}
