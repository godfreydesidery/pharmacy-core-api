package tz.co.pharmacy.modules.insurance.ports.inbound;

import java.util.UUID;

/** An insurer, such as NHIF or Jubilee. */
public record PayerView(
    UUID uid,
    String code,
    String name,
    String contact,
    String email,
    int paymentTermsDays,
    boolean requiresPreauth,
    boolean active) {}
