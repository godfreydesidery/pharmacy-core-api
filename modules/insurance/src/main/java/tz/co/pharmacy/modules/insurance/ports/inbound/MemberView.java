package tz.co.pharmacy.modules.insurance.ports.inbound;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** A customer enrolled in a scheme, with what the scheme has paid for them this year. */
public record MemberView(
    UUID uid,
    UUID customerUid,
    String customerName,
    UUID schemeUid,
    String schemeName,
    String payerName,
    String memberNumber,
    String principalName,
    LocalDate validFrom,
    LocalDate validUntil,
    BigDecimal usedThisYear,
    boolean active,
    UUID payerUid,
    boolean requiresPreauth,
    CopayType copayType,
    BigDecimal copayValue,
    BigDecimal annualLimit) {}
