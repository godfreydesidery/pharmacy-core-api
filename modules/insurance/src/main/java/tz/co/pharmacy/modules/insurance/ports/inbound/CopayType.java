package tz.co.pharmacy.modules.insurance.ports.inbound;

/** What the patient pays of a covered item: nothing, a percent, or a fixed amount per claim. */
public enum CopayType {
  NONE,
  PERCENT,
  FIXED
}
