package tz.co.pharmacy.modules.sales.ports.inbound;

/**
 * Where returned units go (the owner's rule of 23 Nov 2026): back on the shelf, written off as
 * damaged, or held in quarantine until a manager releases them. A prescription or controlled
 * medicine never goes back on the shelf.
 */
public enum ReturnDisposition {
  /** Back to sellable stock, on the batch it was sold from. */
  SHELF,
  /** Not restocked: written off at cost (a DAMAGE stock adjustment). */
  DAMAGED,
  /** Held aside, not sellable, until released to the shelf or written off. */
  QUARANTINE
}
