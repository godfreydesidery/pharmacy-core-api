package tz.co.pharmacy.modules.insurance.ports.inbound;

/** A payer to create or change. */
public record PayerCommand(
    String code,
    String name,
    String contact,
    String email,
    Integer paymentTermsDays,
    Boolean requiresPreauth,
    Boolean active) {}
