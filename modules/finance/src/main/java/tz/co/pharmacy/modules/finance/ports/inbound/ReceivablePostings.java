package tz.co.pharmacy.modules.finance.ports.inbound;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The ledger side of customer accounts and insurance, fed by composition reactions. Each posting is
 * idempotent per (source type, source uid), like the others: a redelivered event posts nothing.
 */
public interface ReceivablePostings {

  /**
   * A payment on account: Dr the payment method's account / Cr 1100. A foreign payment's rounding
   * into the base currency ({@code fxDifference}, exact minus credited) goes to 7000.
   */
  void customerPayment(
      UUID entryUid,
      String reference,
      LocalDate date,
      BigDecimal amount,
      String method,
      BigDecimal fxDifference,
      String currency,
      BigDecimal foreignAmount);

  /** A bad debt: Dr 6096 Bad Debts / Cr 1100. */
  void writeOff(UUID entryUid, String reference, LocalDate date, BigDecimal amount);

  /**
   * A return's refund kept on account while the return was refunded by another method: Dr that
   * method's account / Cr 1100. Nothing when the refund method already credits 1100.
   */
  void creditNote(
      UUID entryUid, String reference, LocalDate date, BigDecimal amount, String refundMethod);

  /** A payer's remittance: Dr 1010 Bank / Cr 1110 Insurance Receivable. */
  void remittance(UUID remittanceUid, String reference, LocalDate date, BigDecimal amount);

  /**
   * The rejected part of a claim: to the patient's account (Dr 1100 / Cr 1110) or written off (Dr
   * 6095 Insurance Rejections / Cr 1110). A negative {@code amount} reverses part of an earlier
   * rejection (Dr 1110 / Cr 6095, or Cr 1100 for the patient's): a re-adjudication that approves
   * more, or goods returned on a written-off claim. {@code claimUid} keys the posting, so a
   * reversal needs its own key.
   */
  void claimRejection(
      UUID claimUid, String reference, LocalDate date, BigDecimal amount, boolean toPatient);

  /**
   * A return's insurer share taken off the claim: the return credited the refund method's account
   * with it, but the insurer had not paid it, so Dr that account / Cr 1110.
   */
  void claimReduction(
      UUID sourceUid, String reference, LocalDate date, BigDecimal amount, String refundMethod);
}
