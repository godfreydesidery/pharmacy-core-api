package tz.co.pharmacy.modules.partners.ports.inbound;

/** {@code paymentTerms} defaults to COD, {@code active} to true. */
public record SupplierCommand(
    String name,
    String contactPerson,
    String phone,
    String email,
    String tin,
    PaymentTerms paymentTerms,
    String address,
    Boolean active) {}
