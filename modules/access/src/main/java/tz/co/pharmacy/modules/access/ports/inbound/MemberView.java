package tz.co.pharmacy.modules.access.ports.inbound;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MemberView(
    UUID membershipUid,
    UUID userUid,
    String name,
    String email,
    UUID roleUid,
    String roleCode,
    String roleName,
    boolean allBranches,
    List<UUID> branchUids,
    boolean active,
    Instant lastSignInAt,
    Instant createdAt,
    String phone,
    boolean managed,
    String contactEmail,
    boolean passwordChangeRequired) {

  /**
   * Where mail to the member goes: their email, or a managed login's contact email ({@code email}
   * is then the sign-in, username@code, which no mail reaches).
   */
  public String mailbox() {
    return managed ? contactEmail : email;
  }
}
