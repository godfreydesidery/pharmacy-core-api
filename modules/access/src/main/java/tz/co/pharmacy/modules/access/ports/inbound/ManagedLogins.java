package tz.co.pharmacy.modules.access.ports.inbound;

import java.util.UUID;

/**
 * Staff logins a pharmacy's admin creates directly, for people with no email or who must start the
 * same day. A managed login belongs to that pharmacy alone: it signs in as {@code username@CODE},
 * starts on a temporary password the person must change at first sign-in, and its admin may reset
 * it. Whether a pharmacy may create them is the root administrator's choice (the caller checks).
 */
public interface ManagedLogins {

  /**
   * 201 with the member, its sign-in and the temporary password, shown this once. 409
   * username_taken; 422 invalid_username and the member checks (role, branches, phone, email).
   */
  Created create(CreateManagedLoginCommand command);

  /**
   * A new temporary password for a managed login of this pharmacy: its sessions end and it must
   * choose its own at the next sign-in. 422 not_managed for a person's own account, whose password
   * only they may change.
   */
  Reset resetPassword(UUID membershipUid);

  record Created(MemberView member, String signIn, String temporaryPassword) {}

  record Reset(MemberView member, String signIn, String temporaryPassword) {}
}
