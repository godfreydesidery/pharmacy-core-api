package tz.co.pharmacy.modules.partners.ports.inbound;

/**
 * {@code paymentTerms} defaults to COD, {@code active} to true, {@code leadTimeDays} (days from
 * order to delivery, 0..365) to 7, or the supplier's current value on a change.
 */
public record SupplierCommand(
    String name,
    String contactPerson,
    String phone,
    String email,
    String tin,
    PaymentTerms paymentTerms,
    String address,
    Boolean active,
    Integer leadTimeDays) {

  public SupplierCommand(
      String name,
      String contactPerson,
      String phone,
      String email,
      String tin,
      PaymentTerms paymentTerms,
      String address,
      Boolean active) {
    this(name, contactPerson, phone, email, tin, paymentTerms, address, active, null);
  }
}
