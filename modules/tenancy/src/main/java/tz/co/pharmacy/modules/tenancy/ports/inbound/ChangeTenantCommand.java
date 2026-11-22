package tz.co.pharmacy.modules.tenancy.ports.inbound;

/**
 * A registry edit by the root administrator. The code never changes; the currency only while the
 * tenant has posted nothing (else 422 currency_locked). {@code directUserCreation} null keeps it.
 */
public record ChangeTenantCommand(
    String name,
    String legalName,
    String country,
    String currency,
    String timezone,
    String contactName,
    String contactEmail,
    String contactPhone,
    String address,
    Boolean directUserCreation) {

  public ChangeTenantCommand(
      String name,
      String legalName,
      String country,
      String currency,
      String timezone,
      String contactName,
      String contactEmail,
      String contactPhone,
      String address) {
    this(
        name,
        legalName,
        country,
        currency,
        timezone,
        contactName,
        contactEmail,
        contactPhone,
        address,
        null);
  }
}
