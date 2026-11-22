package tz.co.pharmacy.modules.access.ports.inbound;

import java.time.Instant;
import java.util.UUID;

public record UserView(
    UUID uid,
    String name,
    String email,
    boolean rootAdmin,
    boolean active,
    Instant lastSignInAt,
    Instant createdAt,
    String phone,
    boolean managed,
    String contactEmail) {

  /** Where mail to the user goes: their email, or a managed login's contact email. */
  public String mailbox() {
    return managed ? contactEmail : email;
  }
}
