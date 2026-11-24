package tz.co.pharmacy.modules.partners.ports.inbound;

import java.util.UUID;

/**
 * One customer's personal data across the modules (STD-5): a copy of everything held about them,
 * and their erasure by anonymisation. Implemented in composition, which reads every module's
 * tables, as the whole pharmacy's export and erasure are ({@code TenantData}).
 */
public interface PersonalData {

  /** The ZIP (subject.json and one CSV per kind of record) and the ACCESS request it answered. */
  record Export(String fileName, byte[] zip, DataSubjectRequestView request) {}

  /**
   * Everything held about the customer: the customer record, consents, sales with lines and
   * prescriptions, insurance memberships and claims, receivable entries, messages sent to their
   * phone or email, adverse-reaction reports and their requests. Completes {@code requestUid} (an
   * OPEN ACCESS request) or logs one; audited. 404 customer_not_found.
   */
  Export export(UUID customerUid, UUID requestUid);

  /**
   * Erasure by anonymisation (verify with counsel, C10): sales, payments, stock and ledgers are
   * append-only and legally kept, so their amounts stay, and the person's name, phone, email,
   * address, insurance number and the patient on their prescriptions, claims and reports are
   * replaced. Refused while the pharmacy is under a legal hold (422 subject_under_legal_hold) or
   * the customer's account has a balance either way (422 subject_has_balance: settle it first). 404
   * customer_not_found; 409 customer_anonymised. Completes or logs an ERASURE request.
   */
  DataSubjectRequestView anonymise(UUID customerUid, UUID requestUid, String note);
}
