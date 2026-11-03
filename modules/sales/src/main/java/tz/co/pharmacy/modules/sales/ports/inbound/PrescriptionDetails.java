package tz.co.pharmacy.modules.sales.ports.inbound;

/** The prescription a PRESCRIPTION or CONTROLLED item is dispensed against. */
public record PrescriptionDetails(
    String prescriberName, String prescriberRegistration, String reference, String patientName) {}
