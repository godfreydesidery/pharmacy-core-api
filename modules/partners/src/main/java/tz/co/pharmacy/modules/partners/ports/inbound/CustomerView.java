package tz.co.pharmacy.modules.partners.ports.inbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CustomerView(
    UUID uid,
    String code,
    String name,
    String phone,
    String email,
    CustomerType type,
    String insuranceProvider,
    String insuranceNumber,
    BigDecimal creditLimit,
    String address,
    boolean active,
    Instant createdAt,
    PaymentTerms paymentTerms,
    /* When the person's data was anonymised (STD-5); null while they are a person on record. */
    Instant anonymisedAt,
    /* The row's version: the ETag of the resource and what If-Match names. */
    long version)
    implements tz.co.pharmacy.platform.kernel.Versioned {}
