package tz.co.pharmacy.modules.tenancy.ports.inbound;

public record RegisterTenantCommand(
    String code,
    String name,
    String legalName,
    String country,
    String currency,
    String timezone,
    BranchCommand headOffice) {}
