package tz.co.pharmacy.modules.access.ports.outbound;

import java.time.Instant;
import java.util.UUID;

/**
 * The messages access sends: a password reset link, an invitation and a new-device sign-in notice.
 * Composition binds it to the notifications module; each is queued in the caller's transaction.
 */
public interface AccessNotices {

  void passwordReset(UUID userUid, String name, String email, String link, int validMinutes);

  /** Sent in the inviting tenant's scope. */
  void invitation(
      UUID tenantUid,
      String name,
      String email,
      String inviterName,
      String roleName,
      String link,
      int validDays);

  void newSignIn(UUID userUid, String name, String email, String device, String ip, Instant at);
}
