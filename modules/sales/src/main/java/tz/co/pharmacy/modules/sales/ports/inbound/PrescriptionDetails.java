package tz.co.pharmacy.modules.sales.ports.inbound;

import java.time.LocalDate;

/**
 * The prescription a PRESCRIPTION or CONTROLLED item is dispensed against (STD-7). For such a sale
 * the prescriber's name and registration number, the patient's name and the prescription's date are
 * required; a CONTROLLED item also needs the prescription's reference. The date may not be after
 * the pharmacy's today, nor older than {@link #VALID_MONTHS} months.
 *
 * <p>Verify with counsel and the Pharmacy Council: the required fields and the validity period (C8
 * in the privacy pack; the Pharmacy Act and its regulations).
 */
public record PrescriptionDetails(
    String prescriberName,
    String prescriberRegistration,
    String reference,
    String patientName,
    LocalDate prescriptionDate) {

  /** How long a prescription can be dispensed against, from its date (verify). */
  public static final int VALID_MONTHS = 6;

  public PrescriptionDetails(
      String prescriberName, String prescriberRegistration, String reference, String patientName) {
    this(prescriberName, prescriberRegistration, reference, patientName, null);
  }
}
