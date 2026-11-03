package tz.co.pharmacy.modules.partners.ports.inbound;

import java.math.BigDecimal;

/**
 * {@code type} defaults to RETAIL, {@code creditLimit} to 0, {@code active} to true and {@code
 * paymentTerms} (how long a credit sale may stay unpaid) to NET_30.
 */
public record CustomerCommand(
    String name,
    String phone,
    String email,
    CustomerType type,
    String insuranceProvider,
    String insuranceNumber,
    BigDecimal creditLimit,
    String address,
    Boolean active,
    PaymentTerms paymentTerms) {

  public CustomerCommand(
      String name,
      String phone,
      String email,
      CustomerType type,
      String insuranceProvider,
      String insuranceNumber,
      BigDecimal creditLimit,
      String address,
      Boolean active) {
    this(
        name,
        phone,
        email,
        type,
        insuranceProvider,
        insuranceNumber,
        creditLimit,
        address,
        active,
        null);
  }
}
