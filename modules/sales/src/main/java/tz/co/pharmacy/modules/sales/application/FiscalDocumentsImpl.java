package tz.co.pharmacy.modules.sales.application;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.pharmacy.modules.sales.domain.FiscalDocument;
import tz.co.pharmacy.modules.sales.domain.Sale;
import tz.co.pharmacy.modules.sales.domain.SaleLine;
import tz.co.pharmacy.modules.sales.domain.SalePayment;
import tz.co.pharmacy.modules.sales.domain.SaleReturn;
import tz.co.pharmacy.modules.sales.domain.SaleReturnLine;
import tz.co.pharmacy.modules.sales.domain.SaleReturnRefund;
import tz.co.pharmacy.modules.sales.domain.SaleTotals;
import tz.co.pharmacy.modules.sales.persistence.FiscalDocumentRepository;
import tz.co.pharmacy.modules.sales.persistence.SaleRepository;
import tz.co.pharmacy.modules.sales.persistence.SaleReturnRepository;
import tz.co.pharmacy.modules.sales.ports.inbound.FiscalDocumentKind;
import tz.co.pharmacy.modules.sales.ports.inbound.FiscalDocumentView;
import tz.co.pharmacy.modules.sales.ports.inbound.FiscalDocuments;
import tz.co.pharmacy.modules.sales.ports.inbound.FiscalStatus;
import tz.co.pharmacy.modules.sales.ports.inbound.PaymentMethod;
import tz.co.pharmacy.modules.sales.ports.outbound.FiscalAnswer;
import tz.co.pharmacy.modules.sales.ports.outbound.FiscalReceipts;
import tz.co.pharmacy.modules.sales.ports.outbound.FiscalRequest;
import tz.co.pharmacy.modules.sales.ports.outbound.SalesSettings;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.kernel.Clocks;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.kernel.TaxCategory;
import tz.co.pharmacy.platform.kernel.TenantCalendar;
import tz.co.pharmacy.platform.persistence.Keyset;
import tz.co.pharmacy.platform.persistence.Specs;

/**
 * The fiscal documents (STD-1). Each is sent in three short transactions with no transaction open
 * while the authority is asked (PERF-1): the lease (and, the first time, the branch's counters),
 * then the call, then the answer. The lease outlives the authority's slowest answer and ends before
 * the outbox delivers the event again, so a crashed instance never holds a document.
 */
@Service
class FiscalDocumentsImpl implements FiscalDocuments {

  private static final Logger LOG = LoggerFactory.getLogger(FiscalDocumentsImpl.class);

  /** How long one attempt may hold a document: longer than VFD's slowest answer (about 30 s). */
  static final Duration LEASE = Duration.ofMinutes(2);

  private static final int MAX_ERROR = 500;

  private static final DateTimeFormatter Z_DAY = DateTimeFormatter.BASIC_ISO_DATE;

  private final FiscalDocumentRepository documents;
  private final SaleRepository sales;
  private final SaleReturnRepository returns;
  private final tz.co.pharmacy.modules.sales.persistence.SaleDebitNoteRepository debitNotes;
  private final FiscalReceipts authority;
  private final SalesSettings settings;
  private final TenantCalendar calendar;
  private final TransactionTemplate transaction;
  private final JdbcTemplate jdbc;

  FiscalDocumentsImpl(
      FiscalDocumentRepository documents,
      SaleRepository sales,
      SaleReturnRepository returns,
      tz.co.pharmacy.modules.sales.persistence.SaleDebitNoteRepository debitNotes,
      FiscalReceipts authority,
      SalesSettings settings,
      TenantCalendar calendar,
      PlatformTransactionManager transactionManager,
      DataSource dataSource) {
    this.debitNotes = debitNotes;
    this.documents = documents;
    this.sales = sales;
    this.returns = returns;
    this.authority = authority;
    this.settings = settings;
    this.calendar = calendar;
    this.transaction = new TransactionTemplate(transactionManager);
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  public boolean enabled() {
    return authority.enabled();
  }

  // --- Written with the sale or the return -----------------------------------------------------

  /** The sale's receipt, PENDING, in the sale's own transaction (one insert). */
  @Transactional(propagation = Propagation.MANDATORY)
  FiscalDocument newReceipt(Sale sale) {
    SalesSettings.Seller seller = settings.seller();
    return documents.save(
        new FiscalDocument(
            FiscalDocumentKind.RECEIPT,
            sale.getBranchUid(),
            authority.provider(),
            sale.getUid(),
            sale.getNumber(),
            sale.getUid(),
            null,
            calendar.today(),
            seller.tin(),
            seller.vrn(),
            figures(
                sale.getLines(),
                SaleLine::category,
                line -> line.getLineTotal().subtract(line.getTax()),
                SaleLine::getTax,
                sale.getTotal())));
  }

  /**
   * A return's credit note, PENDING, in the return's own transaction, when its sale has a fiscal
   * receipt (issued or due); null otherwise.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  FiscalDocument newCreditNote(SaleReturn saleReturn, Sale sale) {
    Map<UUID, SaleLine> sold =
        sale.getLines().stream().collect(Collectors.toMap(SaleLine::getUid, Function.identity()));
    UUID original =
        documents
            .findByKindAndSourceUid(FiscalDocumentKind.RECEIPT, sale.getUid())
            .map(FiscalDocument::getUid)
            .orElse(null);
    SalesSettings.Seller seller = settings.seller();
    return documents.save(
        new FiscalDocument(
            FiscalDocumentKind.CREDIT_NOTE,
            saleReturn.getBranchUid(),
            authority.provider(),
            saleReturn.getUid(),
            saleReturn.getNumber(),
            sale.getUid(),
            original,
            calendar.today(),
            seller.tin(),
            seller.vrn(),
            figures(
                saleReturn.getLines(),
                line -> categoryOf(sold.get(line.getSaleLineUid())),
                line -> line.getRefund().subtract(line.getTax()),
                SaleReturnLine::getTax,
                saleReturn.getRefundTotal())));
  }

  /**
   * A debit note's fiscal document (STD-2), PENDING, in the note's own transaction, when its sale
   * has a fiscal receipt (issued or due); the caller checks {@link #creditNoteFor}.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  FiscalDocument newDebitNote(tz.co.pharmacy.modules.sales.domain.SaleDebitNote note, Sale sale) {
    UUID original =
        documents
            .findByKindAndSourceUid(FiscalDocumentKind.RECEIPT, sale.getUid())
            .map(FiscalDocument::getUid)
            .orElse(null);
    SalesSettings.Seller seller = settings.seller();
    return documents.save(
        new FiscalDocument(
            FiscalDocumentKind.DEBIT_NOTE,
            note.getBranchUid(),
            authority.provider(),
            note.getUid(),
            note.getNumber(),
            sale.getUid(),
            original,
            calendar.today(),
            seller.tin(),
            seller.vrn(),
            figures(
                List.of(note),
                tz.co.pharmacy.modules.sales.domain.SaleDebitNote::getTaxCategory,
                tz.co.pharmacy.modules.sales.domain.SaleDebitNote::getNet,
                tz.co.pharmacy.modules.sales.domain.SaleDebitNote::getTax,
                note.getTotal())));
  }

  /** Issues a debit note's fiscal document when it has one (after commit). */
  void fiscaliseDebitNote(UUID debitNoteUid) {
    if (!authority.enabled()) {
      return;
    }
    UUID document =
        transaction.execute(
            status ->
                documents
                    .findByKindAndSourceUid(FiscalDocumentKind.DEBIT_NOTE, debitNoteUid)
                    .map(FiscalDocument::getUid)
                    .orElse(null));
    if (document != null) {
      issue(document);
    }
  }

  /** Whether a return of this sale gets a credit note: the sale has a fiscal receipt. */
  boolean creditNoteFor(Sale sale) {
    return authority.enabled()
        && (sale.getFiscalStatus() != null || sale.getFiscalReference() != null);
  }

  private static TaxCategory categoryOf(SaleLine line) {
    return line == null ? TaxCategory.EXEMPT : line.category();
  }

  private static <L> FiscalDocument.Figures figures(
      List<L> lines,
      Function<L, TaxCategory> category,
      Function<L, BigDecimal> net,
      Function<L, BigDecimal> tax,
      BigDecimal total) {
    BigDecimal standard = Amounts.ZERO;
    BigDecimal zero = Amounts.ZERO;
    BigDecimal exempt = Amounts.ZERO;
    BigDecimal taxTotal = Amounts.ZERO;
    for (L line : lines) {
      BigDecimal amount = net.apply(line);
      switch (category.apply(line)) {
        case STANDARD -> standard = standard.add(amount);
        case ZERO_RATED -> zero = zero.add(amount);
        case EXEMPT -> exempt = exempt.add(amount);
      }
      taxTotal = taxTotal.add(tax.apply(line));
    }
    return new FiscalDocument.Figures(total, taxTotal, standard, zero, exempt);
  }

  // --- Issuing -----------------------------------------------------------------------------------

  /**
   * Issues the sale's receipt when it is due. A sale made just before receipts were kept as
   * documents (still due, with none) gets its document now.
   */
  void issueReceiptOf(UUID saleUid) {
    if (!authority.enabled()) {
      return;
    }
    UUID document =
        transaction.execute(
            status ->
                documents
                    .findByKindAndSourceUid(FiscalDocumentKind.RECEIPT, saleUid)
                    .or(
                        () ->
                            sales
                                .findByUid(saleUid)
                                .filter(Sale::isFiscalDue)
                                .map(this::newReceipt))
                    .map(FiscalDocument::getUid)
                    .orElse(null));
    if (document != null) {
      issue(document);
    }
  }

  @Override
  public void fiscaliseReturn(UUID returnUid) {
    if (!authority.enabled()) {
      return;
    }
    // In a transaction: the pharmacy's rows are visible only inside one (row-level security).
    UUID document =
        transaction.execute(
            status ->
                documents
                    .findByKindAndSourceUid(FiscalDocumentKind.CREDIT_NOTE, returnUid)
                    .map(FiscalDocument::getUid)
                    .orElse(null));
    if (document != null) {
      issue(document);
    }
  }

  /**
   * Sends the document when it is PENDING and its lease is free; returns its status afterwards.
   * Throws what the authority's call threw (the document stays PENDING, the error kept), and, for a
   * credit note whose receipt is not issued yet, {@link IllegalStateException}.
   */
  FiscalStatus issue(UUID documentUid) {
    Instant now = Clocks.now();
    FiscalRequest request =
        transaction.execute(
            status -> {
              FiscalDocument document = documents.findByUid(documentUid).orElse(null);
              if (document == null || document.getStatus() != FiscalStatus.PENDING) {
                return null;
              }
              String originalNumber = originalNumber(document);
              if (documents.claim(documentUid, now, now.plus(LEASE)) == 0) {
                return null;
              }
              // The seller as the settings are now: a corrected TIN or VRN goes with a resend.
              SalesSettings.Seller seller = settings.seller();
              documents.seller(documentUid, seller.tin(), seller.vrn());
              document = documents.findByUid(documentUid).orElseThrow();
              if (document.getGlobalCounter() == null
                  && document.getKind() != FiscalDocumentKind.Z_REPORT) {
                long[] counters = nextCounters(document.getBranchUid());
                documents.count(documentUid, counters[0], (int) counters[1]);
                document = documents.findByUid(documentUid).orElseThrow();
              }
              return request(document, originalNumber);
            });
    if (request == null) {
      return transaction.execute(
          status -> documents.findByUid(documentUid).map(FiscalDocument::getStatus).orElse(null));
    }
    FiscalAnswer answer;
    try {
      answer = authority.issue(request);
      if (answer == null) {
        throw new IllegalStateException("The fiscal provider gave no answer");
      }
    } catch (RuntimeException failure) {
      transaction.executeWithoutResult(
          status -> documents.attemptFailed(documentUid, error(failure), Clocks.now()));
      throw failure;
    }
    FiscalAnswer settled = answer;
    return transaction.execute(
        status -> {
          FiscalDocument document = documents.findByUid(documentUid).orElseThrow();
          if (settled.issued()) {
            documents.issued(
                documentUid,
                text(settled.receiptNumber(), 100),
                text(settled.verificationCode(), 200),
                text(settled.qrPayload(), 2000),
                Clocks.now());
            beside(document, FiscalStatus.ISSUED, settled.receiptNumber());
            return FiscalStatus.ISSUED;
          }
          documents.refused(
              documentUid,
              text(settled.refusal() == null ? "refused" : settled.refusal(), MAX_ERROR),
              Clocks.now());
          beside(document, FiscalStatus.FAILED, null);
          LOG.warn(
              "The fiscal provider refused {} {}: {}",
              document.getKind(),
              document.getSourceNumber(),
              settled.refusal());
          return FiscalStatus.FAILED;
        });
  }

  /** Writes the document's status beside its sale (a receipt) or its return (a credit note). */
  private void beside(FiscalDocument document, FiscalStatus status, String number) {
    switch (document.getKind()) {
      case RECEIPT -> {
        if (status == FiscalStatus.ISSUED) {
          sales.recordFiscalReceipt(document.getSourceUid(), text(number, 100));
        } else {
          sales.fiscalStatus(document.getSourceUid(), status.name());
        }
      }
      case CREDIT_NOTE ->
          returns.recordCreditNote(document.getSourceUid(), status.name(), text(number, 100));
      case DEBIT_NOTE ->
          debitNotes.recordFiscal(document.getSourceUid(), status.name(), text(number, 100));
      case Z_REPORT -> {
        // A Z report stands alone.
      }
    }
  }

  /**
   * A credit note's receipt number: the receipt document's once issued (else it waits), or a sale's
   * fiscal reference from before documents were kept. Null for the other kinds.
   */
  private String originalNumber(FiscalDocument document) {
    if (document.getKind() != FiscalDocumentKind.CREDIT_NOTE
        && document.getKind() != FiscalDocumentKind.DEBIT_NOTE) {
      return null;
    }
    Optional<FiscalDocument> receipt =
        document.getOriginalDocumentUid() == null
            ? documents.findByKindAndSourceUid(FiscalDocumentKind.RECEIPT, document.getSaleUid())
            : documents.findByUid(document.getOriginalDocumentUid());
    if (receipt.isPresent()) {
      if (receipt.get().getStatus() != FiscalStatus.ISSUED) {
        throw new IllegalStateException(
            document.getKind()
                + " "
                + document.getSourceNumber()
                + " waits for the receipt of sale "
                + receipt.get().getSourceNumber());
      }
      return receipt.get().getReceiptNumber();
    }
    return sales.findByUid(document.getSaleUid()).map(Sale::getFiscalReference).orElse(null);
  }

  /**
   * The branch's next counters, in one atomic upsert: the global one (ever) and the daily one,
   * which starts again at 1 on a new local day.
   */
  private long[] nextCounters(UUID branchUid) {
    return jdbc.queryForObject(
        "insert into sales.fiscal_counters as c (tenant_uid, branch_uid, global_value, day,"
            + " daily_value) values (platform.current_tenant_uid(), ?, 1, ?, 1)"
            + " on conflict (tenant_uid, branch_uid) do update set"
            + " global_value = c.global_value + 1,"
            + " daily_value = case when c.day = excluded.day then c.daily_value + 1 else 1 end,"
            + " day = greatest(c.day, excluded.day)"
            + " returning global_value, daily_value",
        (row, i) -> new long[] {row.getLong(1), row.getLong(2)},
        branchUid,
        java.sql.Date.valueOf(calendar.today()));
  }

  /** What the authority is sent, read from the document and its sale or return. */
  private FiscalRequest request(FiscalDocument document, String originalNumber) {
    SalesSettings.Seller seller = settings.seller();
    FiscalRequest.Seller sender =
        new FiscalRequest.Seller(
            seller.legalName(),
            document.getSellerTin(),
            document.getSellerVrn(),
            seller.vatRegistered());
    FiscalRequest.Totals totals =
        new FiscalRequest.Totals(
            Amounts.money(document.getTotal().subtract(document.getTaxTotal())),
            document.getTaxTotal(),
            document.getTotal(),
            document.getStandardNet(),
            document.getZeroRatedNet(),
            document.getExemptNet());
    String branchCode = settings.branchCode(document.getBranchUid());
    long counter = document.getGlobalCounter() == null ? 0 : document.getGlobalCounter();
    return switch (document.getKind()) {
      case RECEIPT -> {
        Sale sale = sales.findByUid(document.getSourceUid()).orElseThrow();
        yield new FiscalRequest(
            document.getUid(),
            document.getKind(),
            document.getSourceNumber(),
            document.getBranchUid(),
            branchCode,
            document.getBusinessDate(),
            sale.getCreatedAt(),
            sender,
            sale.getCustomerName(),
            sale.getLines().stream().map(FiscalDocumentsImpl::line).toList(),
            payments(sale),
            totals,
            counter,
            document.getDailyCounter(),
            null,
            null);
      }
      case CREDIT_NOTE -> {
        SaleReturn saleReturn = returns.findByUid(document.getSourceUid()).orElseThrow();
        Sale sale = sales.findByUid(saleReturn.getSaleUid()).orElseThrow();
        Map<UUID, SaleLine> sold =
            sale.getLines().stream()
                .collect(Collectors.toMap(SaleLine::getUid, Function.identity()));
        yield new FiscalRequest(
            document.getUid(),
            document.getKind(),
            document.getSourceNumber(),
            document.getBranchUid(),
            branchCode,
            document.getBusinessDate(),
            saleReturn.getCreatedAt(),
            sender,
            sale.getCustomerName(),
            saleReturn.getLines().stream()
                .map(line -> line(line, sold.get(line.getSaleLineUid())))
                .toList(),
            refunds(saleReturn),
            totals,
            counter,
            document.getDailyCounter(),
            originalNumber,
            null);
      }
      case DEBIT_NOTE -> {
        var note = debitNotes.findByUid(document.getSourceUid()).orElseThrow();
        Sale sale = sales.findByUid(note.getSaleUid()).orElseThrow();
        yield new FiscalRequest(
            document.getUid(),
            document.getKind(),
            document.getSourceNumber(),
            document.getBranchUid(),
            branchCode,
            document.getBusinessDate(),
            note.getCreatedAt(),
            sender,
            sale.getCustomerName(),
            List.of(
                new FiscalRequest.Line(
                    "Debit note: " + note.getReason(),
                    1,
                    note.getNet(),
                    Amounts.ZERO,
                    note.getTaxCategory(),
                    note.getTaxRatePercent(),
                    note.getNet(),
                    note.getTax(),
                    note.getTotal())),
            List.of(new FiscalRequest.Payment("CREDIT", note.getTotal())),
            totals,
            counter,
            document.getDailyCounter(),
            originalNumber,
            null);
      }
      case Z_REPORT ->
          new FiscalRequest(
              document.getUid(),
              document.getKind(),
              document.getSourceNumber(),
              document.getBranchUid(),
              branchCode,
              document.getBusinessDate(),
              document.getCreatedAt(),
              sender,
              null,
              List.of(),
              List.of(),
              totals,
              counter,
              null,
              null,
              new FiscalRequest.ZReport(
                  orZero(document.getZReceiptCount()),
                  orZero(document.getZReceiptTotal()),
                  orZero(document.getZReceiptTax()),
                  orZero(document.getZCreditNoteCount()),
                  orZero(document.getZCreditNoteTotal()),
                  orZero(document.getZCreditNoteTax()),
                  documents.firstCounter(document.getUid()),
                  document.getGlobalCounter()));
    };
  }

  private static FiscalRequest.Line line(SaleLine line) {
    return new FiscalRequest.Line(
        line.getProductName(),
        line.getQuantity(),
        line.getUnitPrice(),
        line.getDiscount(),
        line.category(),
        line.getTaxRatePercent(),
        Amounts.money(line.getLineTotal().subtract(line.getTax())),
        line.getTax(),
        line.getLineTotal());
  }

  private static FiscalRequest.Line line(SaleReturnLine line, SaleLine sold) {
    return new FiscalRequest.Line(
        line.getProductName(),
        line.getQuantity(),
        sold == null ? Amounts.ZERO : sold.getUnitPrice(),
        sold == null
            ? Amounts.ZERO
            : SaleTotals.share(sold.getDiscount(), line.getQuantity(), sold.getQuantity()),
        categoryOf(sold),
        sold == null ? BigDecimal.ZERO : sold.getTaxRatePercent(),
        Amounts.money(line.getRefund().subtract(line.getTax())),
        line.getTax(),
        line.getRefund());
  }

  /** The payments in the base currency; the change given back comes off the cash. */
  private static List<FiscalRequest.Payment> payments(Sale sale) {
    List<FiscalRequest.Payment> payments = new ArrayList<>();
    BigDecimal change = sale.getChangeDue() == null ? Amounts.ZERO : sale.getChangeDue();
    for (SalePayment payment : sale.getPayments()) {
      BigDecimal amount =
          payment.getBaseAmount() == null ? payment.getAmount() : payment.getBaseAmount();
      if (change.signum() > 0 && payment.getMethod() == PaymentMethod.CASH) {
        BigDecimal off = change.min(amount);
        amount = amount.subtract(off);
        change = change.subtract(off);
      }
      payments.add(new FiscalRequest.Payment(payment.getMethod().name(), Amounts.money(amount)));
    }
    return payments;
  }

  private static List<FiscalRequest.Payment> refunds(SaleReturn saleReturn) {
    if (saleReturn.getRefunds().isEmpty()) {
      return List.of(
          new FiscalRequest.Payment(
              saleReturn.getRefundMethod().name(), saleReturn.getRefundTotal()));
    }
    List<FiscalRequest.Payment> refunds = new ArrayList<>();
    for (SaleReturnRefund refund : saleReturn.getRefunds()) {
      refunds.add(new FiscalRequest.Payment(refund.getMethod().name(), refund.getAmount()));
    }
    return refunds;
  }

  // --- Retrying, closing days, reading -----------------------------------------------------------

  @Override
  public FiscalDocumentView retry(UUID documentUid) {
    if (!authority.enabled()) {
      throw Refusal.invalid(
          "fiscal_not_enabled", "No fiscal integration is on, so nothing can be sent.");
    }
    UUID branch = ExecutionContext.requireBranch();
    transaction.executeWithoutResult(
        status -> {
          FiscalDocument document =
              documents
                  .findByUid(documentUid)
                  .filter(d -> d.getBranchUid().equals(branch))
                  .orElseThrow(
                      () ->
                          Refusal.notFound(
                              "fiscal_document_not_found", "No such fiscal document here."));
          if (document.getStatus() == FiscalStatus.ISSUED) {
            throw Refusal.conflict(
                    "fiscal_document_issued",
                    "This fiscal document is issued already (" + document.getReceiptNumber() + ").")
                .with("receiptNumber", document.getReceiptNumber());
          }
          if (documents.reopen(documentUid, Clocks.now()) > 0) {
            beside(document, FiscalStatus.PENDING, null);
          }
        });
    sendQuietly(documentUid);
    return transaction.execute(status -> documents.findByUid(documentUid).map(this::view)).get();
  }

  /** Sends the document; a failure is kept on it (and logged), not thrown. */
  private void sendQuietly(UUID documentUid) {
    try {
      issue(documentUid);
    } catch (RuntimeException failure) {
      LOG.warn("Fiscal document {} not issued yet: {}", documentUid, failure.toString());
    }
  }

  @Override
  @Transactional(readOnly = true)
  public List<FiscalDocumentView> ofSale(UUID saleUid) {
    UUID branch = ExecutionContext.requireBranch();
    return documents.findBySaleUidOrderByIdAsc(saleUid).stream()
        .filter(d -> d.getBranchUid().equals(branch))
        .map(this::view)
        .toList();
  }

  /** The sale's documents, in the caller's transaction (the sale's own reads). */
  List<FiscalDocumentView> documentsOf(UUID saleUid) {
    return documents.findBySaleUidOrderByIdAsc(saleUid).stream().map(this::view).toList();
  }

  @Override
  public FiscalDocumentView closeDay(UUID branchUid, LocalDate day) {
    if (!authority.enabled()) {
      throw Refusal.invalid(
          "fiscal_not_enabled", "No fiscal integration is on, so no day is closed.");
    }
    UUID branch = tz.co.pharmacy.platform.kernel.Checks.required(branchUid, "branchUid");
    LocalDate closing = day == null ? calendar.today() : day;
    if (closing.isAfter(calendar.today())) {
      throw Refusal.invalid("invalid_day", "A day is closed once it has begun.", "day")
          .with("today", calendar.today().toString());
    }
    UUID z =
        transaction.execute(
            status -> {
              UUID source = FiscalDocument.zSource(branch, closing);
              // One close of the branch at a time: the second finds the first's report.
              jdbc.queryForObject(
                  "select 1 from (select pg_advisory_xact_lock(hashtextextended(?, 0))) held",
                  Integer.class,
                  "fiscal-z:" + ExecutionContext.requireTenant() + ":" + branch);
              Optional<FiscalDocument> existing =
                  documents.findByKindAndSourceUid(FiscalDocumentKind.Z_REPORT, source);
              if (existing.isPresent()) {
                return existing.get().getUid();
              }
              SalesSettings.Seller seller = settings.seller();
              String code = settings.branchCode(branch);
              FiscalDocument report =
                  documents.saveAndFlush(
                      new FiscalDocument(
                          FiscalDocumentKind.Z_REPORT,
                          branch,
                          authority.provider(),
                          source,
                          "Z-" + code + "-" + Z_DAY.format(closing),
                          null,
                          null,
                          closing,
                          seller.tin(),
                          seller.vrn(),
                          FiscalDocument.Figures.NONE));
              documents.close(report.getUid(), branch, closing);
              documents.sumUp(report.getUid());
              return report.getUid();
            });
    sendQuietly(z);
    return transaction.execute(status -> documents.findByUid(z).map(this::view)).get();
  }

  @Override
  @Transactional(readOnly = true)
  public List<BranchDay> daysToClose(UUID branchUid) {
    if (!authority.enabled()) {
      return List.of();
    }
    List<UUID> branches = branchUid == null ? documents.branchesToClose() : List.of(branchUid);
    if (branches.isEmpty()) {
      return List.of();
    }
    return documents.daysToClose(calendar.today(), branches).stream()
        .map(
            row ->
                new BranchDay(
                    (UUID) row[0],
                    row[1] instanceof java.sql.Date date ? date.toLocalDate() : (LocalDate) row[1]))
        .toList();
  }

  @Override
  public int resend(Duration olderThan, int limit) {
    if (!authority.enabled()) {
      return 0;
    }
    Instant now = Clocks.now();
    List<UUID> pending =
        transaction.execute(
            status -> documents.pendingBefore(now.minus(olderThan), now, Math.max(1, limit)));
    int issued = 0;
    for (UUID uid : pending) {
      try {
        if (issue(uid) == FiscalStatus.ISSUED) {
          issued++;
        }
      } catch (RuntimeException failure) {
        LOG.info("Fiscal document {} not issued yet: {}", uid, failure.toString());
      }
    }
    return issued;
  }

  @Override
  @Transactional(readOnly = true)
  public Page<FiscalDocumentView> zReports(UUID branchUid, PageQuery query) {
    Set<UUID> branches = ExecutionContext.reportBranches();
    if (branchUid != null && branches != null && !branches.contains(branchUid)) {
      throw Refusal.forbidden("branch_not_allowed", "You may not report on this branch.");
    }
    org.springframework.data.jpa.domain.Specification<FiscalDocument> spec =
        Specs.<FiscalDocument>equal("kind", FiscalDocumentKind.Z_REPORT)
            .and(Specs.equal("branchUid", branchUid))
            .and(
                branches == null || branchUid != null
                    ? Specs.<FiscalDocument>all()
                    : Specs.<FiscalDocument>in("branchUid", branches));
    return Keyset.page(documents, spec, query, this::view);
  }

  FiscalDocumentView view(FiscalDocument d) {
    return new FiscalDocumentView(
        d.getUid(),
        d.getKind(),
        d.getStatus(),
        d.getProvider(),
        d.getBranchUid(),
        d.getSourceUid(),
        d.getSourceNumber(),
        d.getSaleUid(),
        d.getBusinessDate(),
        d.getSellerTin(),
        d.getSellerVrn(),
        d.getTotal(),
        d.getTaxTotal(),
        d.getReceiptNumber(),
        d.getVerificationCode(),
        d.getQrPayload(),
        d.getGlobalCounter(),
        d.getDailyCounter(),
        d.getIssuedAt(),
        d.getAttempts(),
        d.getLastError(),
        d.getCreatedAt(),
        d.getKind() == FiscalDocumentKind.Z_REPORT
            ? new FiscalDocumentView.ZFigures(
                orZero(d.getZReceiptCount()),
                orZero(d.getZReceiptTotal()),
                orZero(d.getZReceiptTax()),
                orZero(d.getZCreditNoteCount()),
                orZero(d.getZCreditNoteTotal()),
                orZero(d.getZCreditNoteTax()),
                d.getStandardNet(),
                d.getZeroRatedNet(),
                d.getExemptNet())
            : null);
  }

  private static int orZero(Integer value) {
    return value == null ? 0 : value;
  }

  private static BigDecimal orZero(BigDecimal value) {
    return value == null ? Amounts.ZERO : value;
  }

  private static String error(Throwable failure) {
    return text(failure.toString(), MAX_ERROR);
  }

  private static String text(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() > max ? value.substring(0, max) : value;
  }
}
