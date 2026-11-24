package tz.co.pharmacy.platform.kernel;

/**
 * Where the current tenant is: the country (ISO 3166-1 alpha-2) its local phone numbers belong to
 * ({@link PhoneNumbers}). Outside a tenant, and for a tenant that names none, Tanzania.
 */
public interface TenantLocale {

  /** The current tenant's country, such as "TZ". */
  String country();

  /** {@code raw} in E.164 with the current tenant's country code for a local number, or null. */
  default String phone(String raw) {
    return PhoneNumbers.e164(raw, country());
  }
}
