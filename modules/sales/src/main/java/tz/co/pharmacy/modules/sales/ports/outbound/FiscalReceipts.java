package tz.co.pharmacy.modules.sales.ports.outbound;

/**
 * Issues the pharmacy's fiscal documents with the tax authority: a sale's receipt, a return's
 * credit note and a branch's daily Z report (adversarial review STD-1). In Tanzania this is TRA's
 * VFD (virtual fiscal device) or an EFD; a real adapter needs the authority's specification and the
 * pharmacy's credentials, and implements this port enabled by its own {@code
 * pharmacy.integrations.fiscal.provider} value. The default adapter ({@code none}) issues nothing;
 * the {@code simulator} (sim, local and test profiles only) issues deterministic numbers.
 *
 * <p>It is called after the document's sale or return committed, never inside the transaction that
 * takes the sale number and moves the stock (PERF-1), with no transaction open and a short lease
 * held on the document. The request carries everything a receipt prints: the seller's TIN and VRN,
 * the lines with their VAT category and amounts, the payments, and the branch's global and daily
 * counters, taken once when the document is first sent and kept for every retry.
 *
 * <ul>
 *   <li>{@link FiscalAnswer#issued}: the authority issued it; its number, verification code and QR
 *       payload are kept and the document is ISSUED.
 *   <li>{@link FiscalAnswer#refused}: the authority refused it for good (a TIN it does not know, a
 *       malformed request); the document is FAILED and is not sent again until someone retries it.
 *   <li>A thrown exception: the authority failed or did not answer. The document stays PENDING with
 *       the error and the outbox (or the fiscal job) sends it again. An adapter uses {@link
 *       FiscalRequest#documentUid()} as its idempotency key with the authority, so a retry after a
 *       call whose answer was lost does not issue a second document.
 * </ul>
 */
public interface FiscalReceipts {

  FiscalAnswer issue(FiscalRequest request);

  /** False for the adapter that issues nothing: its sales are never due a document. */
  default boolean enabled() {
    return true;
  }

  /** The provider's name, kept on each document it issues. */
  default String provider() {
    return "external";
  }
}
