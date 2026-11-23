package tz.co.pharmacy.modules.insurance.ports.inbound;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;

/** Insurance of the current tenant; all amounts in the base currency. */
public interface Insurance {

  List<PayerView> payers();

  PayerView createPayer(PayerCommand c);

  PayerView changePayer(UUID uid, PayerCommand c);

  List<SchemeView> schemes(UUID payerUid);

  SchemeView createScheme(SchemeCommand c);

  SchemeView changeScheme(UUID uid, SchemeCommand c);

  Page<MemberView> members(String q, PageQuery query);

  List<MemberView> membersOf(UUID customerUid);

  MemberView enrol(MemberCommand c);

  MemberView changeMember(UUID uid, MemberCommand c);

  Optional<MemberView> memberOf(UUID customerUid, LocalDate on);

  /**
   * The split of a basket: excluded items are not covered (the patient pays them), the scheme's
   * co-pay comes off the rest, and the insurer's share is capped at what remains of the member's
   * annual limit. 422 member_inactive, 422 preauth_required.
   */
  CoverageQuote quote(UUID memberUid, List<CoverageLine> lines, String preauthNumber);

  /** Checkout calls it in the sale's transaction; CLM-000001, DRAFT. */
  ClaimView createClaim(ClaimCommand c);

  Page<ClaimView> claims(UUID payerUid, ClaimStatus status, PageQuery query);

  Optional<ClaimView> claim(UUID uid);

  Optional<ClaimView> claimForSale(UUID saleUid);

  /**
   * A return of an insured sale: the insurer's share of the refund comes off the claim as a
   * negative line, or a DRAFT claim fully returned is VOIDED. On an adjudicated claim it comes off
   * what the payer still owes (approved − paid) first, then off the rejected part the books hold
   * (the patient's charge, then the write-off), so 1110 is never reduced for value it no longer
   * holds; what is left (a share the payer already paid) leaves the claim owing the payer that
   * much. Publishes insurance.claim_reduced.
   */
  Optional<ClaimView> reduceForReturn(
      UUID saleUid, BigDecimal amount, String reference, String refundMethod);

  /** Gathers the payer's DRAFT claims of the period into a batch; they become SUBMITTED. */
  ClaimBatchView batch(UUID payerUid, LocalDate from, LocalDate to);

  Page<ClaimBatchView> batches(UUID payerUid, PageQuery query);

  /** CSV, or JSON: a FHIR R4 Bundle of Claim resources. */
  byte[] exportBatch(UUID batchUid, String format);

  /**
   * Records the payer's decision; a claim may be adjudicated again until it is PAID (an appeal).
   * Each decision publishes insurance.claim_adjudicated with the change in what is written off and
   * charged to the patient, so the books move by the difference, never by the whole rejection
   * again. 422 claim_closed for a PAID or VOIDED claim.
   */
  ClaimView adjudicate(UUID claimUid, AdjudicationCommand c);

  /**
   * Publishes insurance.remittance_received. 422 invalid_amount for an amount, or a perClaim
   * amount, that is not more than zero.
   */
  List<ClaimView> remit(RemittanceCommand c);

  Page<RemittanceView> remittances(UUID payerUid, PageQuery query);

  ClaimTotals totals();
}
