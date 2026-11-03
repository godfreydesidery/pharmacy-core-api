package tz.co.pharmacy.modules.insurance.ports.inbound;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

/** A scheme to create or change. */
public record SchemeCommand(
    UUID payerUid,
    String name,
    CopayType copayType,
    BigDecimal copayValue,
    BigDecimal annualLimit,
    Set<String> excludedProductTypes,
    Set<UUID> excludedProductUids,
    Boolean active) {}
