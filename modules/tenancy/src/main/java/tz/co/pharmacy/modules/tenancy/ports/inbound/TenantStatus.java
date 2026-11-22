package tz.co.pharmacy.modules.tenancy.ports.inbound;

/**
 * Whether a tenant trades: a suspended tenant's members are refused (an unpaid one's admins may
 * still read); a closed tenant's admins may only read and export until it is erased or reopened; an
 * erased tenant is a tombstone. A root administrator is never refused.
 */
public enum TenantStatus {
  ACTIVE,
  SUSPENDED,
  CLOSED,
  ERASED
}
