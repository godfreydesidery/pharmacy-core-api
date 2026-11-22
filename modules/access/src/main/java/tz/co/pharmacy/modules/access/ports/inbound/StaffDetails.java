package tz.co.pharmacy.modules.access.ports.inbound;

/**
 * What a pharmacy keeps about someone who works for it (G15): its own staff number (unique in the
 * pharmacy), a job title, a work phone and a work email. Kept on the membership: they never touch
 * the person's own account and no other pharmacy sees them.
 */
public record StaffDetails(
    String staffNumber, String jobTitle, String workPhone, String workEmail) {

  public static final StaffDetails NONE = new StaffDetails(null, null, null, null);
}
