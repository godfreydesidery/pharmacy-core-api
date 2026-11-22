package tz.co.pharmacy.modules.tenancy.ports.inbound;

/** Why a pharmacy is suspended, and so what it may still do. */
public enum SuspensionKind {
  /** Its subscription lapsed: admins keep read-only access, to pay and take their data. */
  UNPAID,
  /** A legal hold: nobody works in it, and its data may not be erased until the hold is lifted. */
  DISPUTE,
  /** Misuse of the platform: blocked. */
  ABUSE,
  /** Another reason, given in the note: blocked. */
  OTHER
}
