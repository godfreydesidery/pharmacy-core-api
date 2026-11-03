package tz.co.pharmacy.modules.tenancy.events;

import java.util.UUID;
import tz.co.pharmacy.platform.kernel.DomainEvent;
import tz.co.pharmacy.platform.kernel.EventMetadata;

/** "tenant.registered": a pharmacy business joined the platform; its metadata names the tenant. */
public record TenantRegistered(EventMetadata metadata, UUID tenantUid, String code, String name)
    implements DomainEvent {}
