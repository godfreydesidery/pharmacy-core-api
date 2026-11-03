package tz.co.pharmacy.modules.tenancy.ports.inbound;

/**
 * Whether a tenant trades: a suspended tenant's members are refused; a root administrator is not.
 */
public enum TenantStatus {
  ACTIVE,
  SUSPENDED
}
