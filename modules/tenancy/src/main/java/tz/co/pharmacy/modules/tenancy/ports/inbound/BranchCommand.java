package tz.co.pharmacy.modules.tenancy.ports.inbound;

public record BranchCommand(
    String code, String name, String address, String phone, String licenceNumber) {}
