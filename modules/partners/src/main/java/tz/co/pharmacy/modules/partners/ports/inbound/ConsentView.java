package tz.co.pharmacy.modules.partners.ports.inbound;

import java.time.Instant;
import java.util.UUID;

/**
 * One consent record: given (and by whom), then perhaps withdrawn. A record with no {@code givenAt}
 * is a refusal noted when the customer was asked. {@code inForce}: given and not withdrawn.
 */
public record ConsentView(
    UUID uid,
    ConsentPurpose purpose,
    Instant givenAt,
    String givenBy,
    Instant withdrawnAt,
    String withdrawnBy,
    String note,
    boolean inForce) {}
