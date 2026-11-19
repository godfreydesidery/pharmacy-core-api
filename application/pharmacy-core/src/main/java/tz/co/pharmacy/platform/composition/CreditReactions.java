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

  @Async
  @TransactionalEventListener
  public void on(ClaimReduced event) {
    post(
        event.metadata(),
        () ->
            postings.claimReduction(
                event.metadata().eventId(),
                event.reference(),
                day(event.metadata()),
                event.amount(),
                event.refundMethod()));
  }

  /** The rejected part to the ledger: written off, or moved to the patient's receivable. */
  @Async
  @TransactionalEventListener
  public void onRejectionPosting(ClaimAdjudicated event) {
    if (event.rejected() == null || event.rejected().signum() <= 0) {
      return;
    }
    boolean toPatient = "PATIENT_ACCOUNT".equals(event.rejectedTo()) && event.customerUid() != null;
    post(
        event.metadata(),
        () ->
            postings.claimRejection(
                event.metadata().eventId(),
                event.claimNumber(),
                day(event.metadata()),
                event.rejected(),
                toPatient));
  }

  /** Scenario 11: the patient now owes the rejected part, as a charge on their account. */
  @Async
  @TransactionalEventListener
  public void onRejectionCharge(ClaimAdjudicated event) {
    BigDecimal rejected = event.rejected();
    if (rejected == null
        || rejected.signum() <= 0
        || !"PATIENT_ACCOUNT".equals(event.rejectedTo())
        || event.customerUid() == null) {
      return;
    }
    UUID sale = event.saleUid();
    EventScopes.run(
        event.metadata(),
        ACCOUNTS,
        () ->
            credit.charge(
                event.customerUid(),
                sale,
                "Rejected by insurer: " + event.claimNumber(),
                rejected,
                day(event.metadata())));
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
