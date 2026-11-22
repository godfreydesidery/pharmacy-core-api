package tz.co.pharmacy.modules.access.ports.inbound;

import java.util.UUID;

/**
 * A signed-in session working in a pharmacy. The first time a session works there, that pharmacy's
 * audit records the sign-in; and the member's "last active here" moves on (written at most every
 * few minutes). A pharmacy so learns only of work done in it, never of sign-ins to work somewhere
 * else.
 */
public interface PharmacyVisits {

  /** Session {@code sessionUid} of {@code userUid} is working in {@code tenantUid}. */
  void working(UUID sessionUid, UUID userUid, UUID tenantUid);
}
