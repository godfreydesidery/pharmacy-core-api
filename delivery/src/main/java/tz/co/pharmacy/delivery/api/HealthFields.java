package tz.co.pharmacy.delivery.api;

import tz.co.pharmacy.modules.sales.ports.inbound.SaleView;
import tz.co.pharmacy.platform.kernel.ExecutionContext;

/**
 * Health details are sensitive personal data (Tanzania's PDPA 2022, adversarial review STD-5): a
 * sale's prescription (patient, prescriber), the controlled-drugs register's patients, insurance
 * diagnosis codes and adverse-reaction reports are shown only to holders of {@code health.view}.
 * Whoever lacks it sees that a prescription exists, not what it says. Reads of a prescription's
 * detail are audited by the routes that show one.
 *
 * <p>Not encrypted at rest beyond the database's own storage encryption: the keyring of Batch 6
 * seals secrets by purpose, and sealing these fields would take the register's and the lookups'
 * search away for little gain while the application role reads them anyway.
 */
public final class HealthFields {

  public static final String PERMISSION = "health.view";

  private HealthFields() {}

  /** Whether the caller may read health details. */
  public static boolean visible() {
    return ExecutionContext.permits(PERMISSION);
  }

  /** The claim as the caller may see it: its diagnosis code (ICD-10) is health data. */
  public static tz.co.pharmacy.modules.insurance.ports.inbound.ClaimView claim(
      tz.co.pharmacy.modules.insurance.ports.inbound.ClaimView claim) {
    return claim == null || visible() ? claim : claim.withoutDiagnosis();
  }

  /** The sale as the caller may see it. */
  public static SaleView sale(SaleView sale) {
    return sale == null || visible() ? sale : sale.withoutHealthDetails();
  }
}
