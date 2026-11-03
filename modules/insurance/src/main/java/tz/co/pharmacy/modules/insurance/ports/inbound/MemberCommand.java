package tz.co.pharmacy.modules.insurance.ports.inbound;

import java.time.LocalDate;
import java.util.UUID;

/** An enrolment to create or change. */
public record MemberCommand(
    UUID customerUid,
    UUID schemeUid,
    String memberNumber,
    String principalName,
    LocalDate validFrom,
    LocalDate validUntil,
    Boolean active) {}
