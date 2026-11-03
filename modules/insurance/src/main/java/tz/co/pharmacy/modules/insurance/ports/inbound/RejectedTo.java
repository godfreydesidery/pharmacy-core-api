package tz.co.pharmacy.modules.insurance.ports.inbound;

/**
 * Where the rejected part of a claim goes: written off (6095) or charged to the patient's account.
 */
public enum RejectedTo {
  WRITE_OFF,
  PATIENT_ACCOUNT
}
