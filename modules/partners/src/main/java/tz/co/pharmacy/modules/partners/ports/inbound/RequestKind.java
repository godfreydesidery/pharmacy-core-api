package tz.co.pharmacy.modules.partners.ports.inbound;

/** The right a data-subject request exercises (PDPA 2022). */
public enum RequestKind {
  ACCESS,
  CORRECTION,
  ERASURE,
  OBJECTION,
  RESTRICTION
}
