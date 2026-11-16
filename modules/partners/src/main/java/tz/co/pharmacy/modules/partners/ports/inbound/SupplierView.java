package tz.co.pharmacy.modules.partners.ports.inbound;

import java.time.Instant;
import java.util.UUID;

public record SupplierView(
    UUID uid,
    String code,
    String name,
    String contactPerson,
    String phone,
    String email,
    String tin,
    PaymentTerms paymentTerms,
    String address,
    boolean active,
    Instant createdAt,
    int leadTimeDays) {}
