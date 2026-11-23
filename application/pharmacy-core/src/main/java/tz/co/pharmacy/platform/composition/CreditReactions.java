package tz.co.pharmacy.platform.composition;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import tz.co.pharmacy.modules.finance.ports.inbound.ReceivablePostings;
import tz.co.pharmacy.modules.insurance.events.ClaimAdjudicated;
import tz.co.pharmacy.modules.insurance.events.ClaimReduced;
import tz.co.pharmacy.modules.insurance.events.RemittanceReceived;
import tz.co.pharmacy.modules.receivables.events.CreditNoteIssued;
import tz.co.pharmacy.modules.receivables.events.DebtWrittenOff;
import tz.co.pharmacy.modules.receivables.events.PaymentReceived;
import tz.co.pharmacy.modules.receivables.ports.inbound.CustomerCredit;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantService;
import tz.co.pharmacy.platform.bus.EventScopes;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.kernel.Clocks;
import tz.co.pharmacy.platform.kernel.EventMetadata;

/**
 * Turns customer-account and insurance events into journal entries, on the tenant's local date, and
 * charges a claim's rejected part to the patient's account when the adjudicator chose so. Postings
 * are idempotent per source; a duplicate that slips past the check is logged.
 */
@Component
public class CreditReactions {

  private static final Logger LOG = LoggerFactory.getLogger(CreditReactions.class);
  private static final String LEDGER = "credit-ledger-posting";
  private static final String ACCOUNTS = "claim-rejections";

  private final ReceivablePostings postings;
  private final CustomerCredit credit;
  private final TenantService tenants;

  public CreditReactions(
      ReceivablePostings postings, CustomerCredit credit, TenantService tenants) {
    this.postings = postings;
    this.credit = credit;
    this.tenants = tenants;
  }

  @Async
  @TransactionalEventListener
  public void on(PaymentReceived event) {
    post(
        event.metadata(),
        () ->
            postings.customerPayment(
                event.entryUid(),
                event.reference(),
                day(event.metadata()),
                event.amount(),
                event.method(),
                event.fxDifference(),
                event.currency(),
                event.foreignAmount()));
  }

  @Async
  @TransactionalEventListener
  public void on(DebtWrittenOff event) {
    post(
        event.metadata(),
        () ->
            postings.writeOff(
                event.entryUid(), event.reference(), day(event.metadata()), event.amount()));
  }

  @Async
  @TransactionalEventListener
  public void on(CreditNoteIssued event) {
    post(
        event.metadata(),
        () ->
            postings.creditNote(
                event.entryUid(),
                event.reference(),
                day(event.metadata()),
                event.amount(),
                event.refundMethod()));
  }

  @Async
  @TransactionalEventListener
  public void on(RemittanceReceived event) {
    post(
        event.metadata(),
        () ->
            postings.remittance(
                event.remittanceUid(),
                event.number() + (event.reference() == null ? "" : " " + event.reference()),
                event.date() == null ? day(event.metadata()) : event.date(),
                event.amount()));
  }

  /**
   * A return's insurer share, where the claim held it (MON-5): off 1110 for what the payer still
   * owed; for a rejected part written off, the same Dr refund / Cr 1110 and then the write-off
   * given back (Dr 1110 / Cr 6095), so 1110 moves only by what it held; for a rejected part charged
   * to the patient, a credit note on their account (Dr refund / Cr 1100) instead of 1110.
   */
  @Async
  @TransactionalEventListener
  public void on(ClaimReduced event) {
    BigDecimal receivable =
        event.receivableReduction() == null ? event.amount() : event.receivableReduction();
    BigDecimal writeOff = orZero(event.writeOffReversal());
    post(
        event.metadata(),
        () ->
            postings.claimReduction(
                event.metadata().eventId(),
                event.reference(),
                day(event.metadata()),
                receivable.add(writeOff),
                event.refundMethod()));
    if (writeOff.signum() > 0) {
      post(
          event.metadata(),
          () ->
              postings.claimRejection(
                  derived(event.metadata(), "write-off"),
                  event.reference(),
                  day(event.metadata()),
                  writeOff.negate(),
                  false));
    }
  }

  /** The patient's charged share of a return comes off their account, settling that charge. */
  @Async
  @TransactionalEventListener
  public void onReductionCredit(ClaimReduced event) {
    BigDecimal patient = orZero(event.patientReduction());
    if (patient.signum() <= 0 || event.customerUid() == null || event.saleUid() == null) {
      return;
    }
    EventScopes.run(
        event.metadata(),
        ACCOUNTS,
        () ->
            credit.creditNote(
                event.customerUid(),
                event.saleUid(),
                event.reference(),
                patient,
                event.refundMethod()));
  }

  /**
   * The change in the rejected part to the ledger (MON-4): more written off (Dr 6095 / Cr 1110) or
   * given back (the reverse), more charged to the patient (Dr 1100 / Cr 1110). Less charged to the
   * patient goes through their account as a credit note ({@link #onRejectionCharge}), which posts
   * its own Dr 1110 / Cr 1100. Each change is keyed by the adjudication, so a redelivery posts
   * nothing and a second adjudication posts only its difference.
   */
  @Async
  @TransactionalEventListener
  public void onRejectionPosting(ClaimAdjudicated event) {
    boolean toPatient = "PATIENT_ACCOUNT".equals(event.rejectedTo()) && event.customerUid() != null;
    BigDecimal writeOff;
    BigDecimal patient;
    if (event.writeOffChange() == null && event.patientChange() == null) {
      // An event from before the changes were carried: the first adjudication's whole rejection.
      writeOff = toPatient ? Amounts.ZERO : orZero(event.rejected());
      patient = toPatient ? orZero(event.rejected()) : Amounts.ZERO;
    } else {
      writeOff = orZero(event.writeOffChange());
      patient = orZero(event.patientChange());
    }
    if (writeOff.signum() != 0) {
      post(
          event.metadata(),
          () ->
              postings.claimRejection(
                  event.metadata().eventId(),
                  event.claimNumber(),
                  day(event.metadata()),
                  writeOff,
                  false));
    }
    if (patient.signum() > 0) {
      post(
          event.metadata(),
          () ->
              postings.claimRejection(
                  writeOff.signum() == 0
                      ? event.metadata().eventId()
                      : derived(event.metadata(), "patient"),
                  event.claimNumber(),
                  day(event.metadata()),
                  patient,
                  true));
    }
  }

  /**
   * Scenario 11: the patient now owes the rejected part, as a charge on their account; a later
   * decision that charges them less gives the difference back as a credit note on the sale.
   */
  @Async
  @TransactionalEventListener
  public void onRejectionCharge(ClaimAdjudicated event) {
    if (event.customerUid() == null) {
      return;
    }
    BigDecimal patient;
    if (event.patientChange() == null && event.writeOffChange() == null) {
      patient =
          "PATIENT_ACCOUNT".equals(event.rejectedTo()) ? orZero(event.rejected()) : Amounts.ZERO;
    } else {
      patient = orZero(event.patientChange());
    }
    if (patient.signum() == 0) {
      return;
    }
    UUID sale = event.saleUid();
    EventScopes.run(
        event.metadata(),
        ACCOUNTS,
        () -> {
          if (patient.signum() > 0) {
            credit.charge(
                event.customerUid(),
                sale,
                "Rejected by insurer: " + event.claimNumber(),
                patient,
                day(event.metadata()));
          } else {
            credit.creditNote(
                event.customerUid(),
                sale,
                "Re-adjudicated: " + event.claimNumber(),
                patient.negate(),
                "INSURANCE");
          }
        });
  }

  private static BigDecimal orZero(BigDecimal value) {
    return value == null ? Amounts.ZERO : value;
  }

  /** A second posting of one event gets its own source key, stable across redeliveries. */
  private static UUID derived(EventMetadata metadata, String part) {
    return UUID.nameUUIDFromBytes(
        (metadata.eventId() + ":" + part).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private void post(EventMetadata metadata, Runnable posting) {
    EventScopes.run(
        metadata,
        LEDGER,
        () -> {
          try {
            posting.run();
          } catch (DataIntegrityViolationException alreadyPosted) {
            LOG.info(
                "Journal entry already posted: {}",
                alreadyPosted.getMostSpecificCause().getMessage());
          }
        });
  }

  private LocalDate day(EventMetadata metadata) {
    ZoneId zone =
        tenants
            .find(metadata.tenantUid())
            .map(tenant -> ZoneId.of(tenant.timezone()))
            .orElse(ZoneOffset.UTC);
    Instant at = metadata.occurredAt() == null ? Clocks.now() : metadata.occurredAt();
    return LocalDate.ofInstant(at, zone);
  }
}
