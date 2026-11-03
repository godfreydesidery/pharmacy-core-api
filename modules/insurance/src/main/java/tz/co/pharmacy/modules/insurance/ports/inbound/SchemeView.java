package tz.co.pharmacy.modules.insurance.ports.inbound;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

/** A payer's scheme: its co-pay, annual limit per member, and what it does not cover. */
public record SchemeView(
    UUID uid,
    UUID payerUid,
    String payerName,
    String name,
    CopayType copayType,
    BigDecimal copayValue,
    BigDecimal annualLimit,
    Set<String> excludedProductTypes,
    Set<UUID> excludedProductUids,
    boolean active) {}
