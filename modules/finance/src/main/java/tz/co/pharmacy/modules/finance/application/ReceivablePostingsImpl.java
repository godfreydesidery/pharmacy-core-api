package tz.co.pharmacy.modules.finance.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.finance.ports.inbound.ReceivablePostings;
import tz.co.pharmacy.platform.kernel.Amounts;

/** Journal entries of customer accounts and insurance. */
@Service
class ReceivablePostingsImpl implements ReceivablePostings {

  static final String CUSTOMER_PAYMENT = "CUSTOMER_PAYMENT";
  static final String BAD_DEBT = "BAD_DEBT";
  static final String CREDIT_NOTE = "CREDIT_NOTE";
  static final String REMITTANCE = "INSURANCE_REMITTANCE";
  static final String CLAIM_REJECTION = "CLAIM_REJECTION";
  static final String CLAIM_REDUCTION = "CLAIM_REDUCTION";

  static final String FX = "7000";
  static final String INSURANCE_REJECTIONS = "6095";
  static final String BAD_DEBTS = "6096";

  private final JournalWriter writer;

  ReceivablePostingsImpl(JournalWriter writer) {
    this.writer = writer;
  }

  @Override
  @Transactional
  public void customerPayment(
      UUID entryUid,
      String reference,
      LocalDate date,
      BigDecimal amount,
      String method,
      BigDecimal fxDifference,
      String currency,
      BigDecimal foreignAmount) {
    writer.post(
        CUSTOMER_PAYMENT,
        entryUid,
        reference,
        "Customer payment " + reference,
        null,
        date,
        customerPaymentLines(amount, method, fxDifference, currency, foreignAmount));
  }

  /**
   * Dr money in / Cr receivable for the credited base amount. The sub-cent difference of a foreign
   * payment (exact − credited) is rounded to cents and balanced through 7000; below a cent it posts
   * nothing.
   */
  static List<Postings.Line> customerPaymentLines(
      BigDecimal amount,
      String method,
      BigDecimal fxDifference,
      String currency,
      BigDecimal foreignAmount) {
    List<Postings.Line> lines = new ArrayList<>();
    BigDecimal credited = Amounts.money(amount);
    BigDecimal difference = fxDifference == null ? Amounts.ZERO : Amounts.money(fxDifference);
    BigDecimal received = Amounts.money(credited.add(difference));
    lines.add(
        new Postings.Line(
            Postings.paymentAccount(method), received, Amounts.ZERO, currency, foreignAmount));
    lines.add(new Postings.Line(Postings.RECEIVABLE, Amounts.ZERO, credited));
    if (difference.signum() > 0) {
      lines.add(new Postings.Line(FX, Amounts.ZERO, difference));
    } else if (difference.signum() < 0) {
      lines.add(new Postings.Line(FX, difference.negate(), Amounts.ZERO));
    }
    return lines;
  }

  @Override
  @Transactional
  public void writeOff(UUID entryUid, String reference, LocalDate date, BigDecimal amount) {
    writer.post(
        BAD_DEBT,
        entryUid,
        reference,
        "Bad debt written off " + reference,
        null,
        date,
        pair(BAD_DEBTS, Postings.RECEIVABLE, amount));
  }

  @Override
  @Transactional
  public void creditNote(
      UUID entryUid, String reference, LocalDate date, BigDecimal amount, String refundMethod) {
    String refunded = Postings.paymentAccount(refundMethod);
    if (Postings.RECEIVABLE.equals(refunded)) {
      return;
    }
    writer.post(
        CREDIT_NOTE,
        entryUid,
        reference,
        "Return kept on account " + reference,
        null,
        date,
        pair(refunded, Postings.RECEIVABLE, amount));
  }

  @Override
  @Transactional
  public void remittance(UUID remittanceUid, String reference, LocalDate date, BigDecimal amount) {
    writer.post(
        REMITTANCE,
        remittanceUid,
        reference,
        "Insurance remittance " + reference,
        null,
        date,
        pair(Postings.BANK, Postings.INSURANCE_RECEIVABLE, amount));
  }

  @Override
  @Transactional
  public void claimRejection(
      UUID claimUid, String reference, LocalDate date, BigDecimal amount, boolean toPatient) {
    writer.post(
        CLAIM_REJECTION,
        claimUid,
        reference,
        (toPatient ? "Claim rejection charged to patient " : "Claim rejection written off ")
            + reference,
        null,
        date,
        pair(
            toPatient ? Postings.RECEIVABLE : INSURANCE_REJECTIONS,
            Postings.INSURANCE_RECEIVABLE,
            amount));
  }

  @Override
  @Transactional
  public void claimReduction(
      UUID sourceUid, String reference, LocalDate date, BigDecimal amount, String refundMethod) {
    String refunded = Postings.paymentAccount(refundMethod);
    if (Postings.INSURANCE_RECEIVABLE.equals(refunded)) {
      return;
    }
    writer.post(
        CLAIM_REDUCTION,
        sourceUid,
        reference,
        "Insurer share of return " + reference,
        null,
        date,
        pair(refunded, Postings.INSURANCE_RECEIVABLE, amount));
  }

  private static List<Postings.Line> pair(String debit, String credit, BigDecimal amount) {
    BigDecimal value = Amounts.money(amount);
    if (value.signum() <= 0) {
      return List.of();
    }
    return List.of(
        new Postings.Line(debit, value, Amounts.ZERO),
        new Postings.Line(credit, Amounts.ZERO, value));
  }
}
