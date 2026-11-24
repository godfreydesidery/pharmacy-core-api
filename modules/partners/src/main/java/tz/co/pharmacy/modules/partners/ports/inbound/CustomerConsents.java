package tz.co.pharmacy.modules.partners.ports.inbound;

import java.util.Map;
import java.util.UUID;

/**
 * Customers' consent records (STD-5, Tanzania's PDPA 2022): purpose, given and withdrawn moments,
 * and who recorded each. Kept when a customer is anonymised: they prove what was agreed and hold no
 * personal data.
 */
public interface CustomerConsents {

  /** 404 customer_not_found. */
  ConsentsView consents(UUID customerUid);

  /**
   * Records that the customer gives ({@code given}) or withdraws consent to the purpose; nothing
   * when it is already so. 404 customer_not_found; 409 customer_anonymised.
   */
  ConsentsView record(UUID customerUid, ConsentPurpose purpose, boolean given, String note);

  /** The customer form's choices: records only the purposes whose state changes. */
  void apply(UUID customerUid, Map<ConsentPurpose, Boolean> choices);

  /**
   * Whether a message of the purpose may go to the customer: consent in force or, for SMS_RECEIPTS,
   * nothing withdrawn. Every sender of customer messages asks this first (the SMS receipt does;
   * there is no marketing sender yet, and one must).
   */
  boolean allows(UUID customerUid, ConsentPurpose purpose);
}
