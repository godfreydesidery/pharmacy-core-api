package tz.co.pharmacy.modules.tenancy.ports.inbound;

public record PlatformCounts(long tenants, long activeTenants, long suspendedTenants) {}
