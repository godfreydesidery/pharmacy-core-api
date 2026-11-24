package tz.co.pharmacy.modules.sales.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.sales.domain.RefundSplit;
import tz.co.pharmacy.modules.sales.domain.Sale;
import tz.co.pharmacy.modules.sales.domain.SaleLine;
import tz.co.pharmacy.modules.sales.domain.SalePayment;
import tz.co.pharmacy.modules.sales.domain.SaleReturn;
import tz.co.pharmacy.modules.sales.domain.SaleReturnLine;
import tz.co.pharmacy.modules.sales.domain.SaleReturnRefund;
import tz.co.pharmacy.modules.sales.domain.SaleTotals;
import tz.co.pharmacy.modules.sales.events.SaleCompleted;
import tz.co.pharmacy.modules.sales.events.SaleReturnReleased;
import tz.co.pharmacy.modules.sales.events.SaleReturned;
import tz.co.pharmacy.modules.sales.persistence.SaleRepository;
import tz.co.pharmacy.modules.sales.persistence.SaleReturnRepository;
import tz.co.pharmacy.modules.sales.ports.inbound.PaymentCommand;
import tz.co.pharmacy.modules.sales.ports.inbound.PaymentMethod;
import tz.co.pharmacy.modules.sales.ports.inbound.PaymentView;
import tz.co.pharmacy.modules.sales.ports.inbound.PrescriptionDetails;
import tz.co.pharmacy.modules.sales.ports.inbound.RecordReturnCommand;
import tz.co.pharmacy.modules.sales.ports.inbound.RecordSaleCommand;
import tz.co.pharmacy.modules.sales.ports.inbound.ReturnDisposition;
import tz.co.pharmacy.modules.sales.ports.inbound.ReturnLineCommand;
import tz.co.pharmacy.modules.sales.ports.inbound.SaleFilter;
import tz.co.pharmacy.modules.sales.ports.inbound.SaleLineCommand;
import tz.co.pharmacy.modules.sales.ports.inbound.SaleLineView;
import tz.co.pharmacy.modules.sales.ports.inbound.SaleRefundView;
import tz.co.pharmacy.modules.sales.ports.inbound.SaleReturnLineView;
import tz.co.pharmacy.modules.sales.ports.inbound.SaleReturnView;
import tz.co.pharmacy.modules.sales.ports.inbound.SaleService;
import tz.co.pharmacy.modules.sales.ports.inbound.SaleSummaryView;
import tz.co.pharmacy.modules.sales.ports.inbound.SaleView;
import tz.co.pharmacy.modules.sales.ports.outbound.FiscalReceipts;
import tz.co.pharmacy.modules.sales.ports.outbound.PaymentAuthorizations;
import tz.co.pharmacy.modules.sales.ports.outbound.PaymentAuthorizations.PaymentAuthorization;
import tz.co.pharmacy.modules.sales.ports.outbound.SalesProducts;
import tz.co.pharmacy.modules.sales.ports.outbound.SalesSettings;
import tz.co.pharmacy.platform.approval.ApprovalActions;
import tz.co.pharmacy.platform.approval.Approvals;
import tz.co.pharmacy.platform.bus.DomainEventPublisher;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.kernel.TenantCalendar;
import tz.co.pharmacy.platform.persistence.DocumentNumbers;
import tz.co.pharmacy.platform.persistence.Keyset;
import tz.co.pharmacy.platform.persistence.Specs;

@Service
@RequiredArgsConstructor
class SaleServiceImpl implements SaleService {

  static final String DISCOUNT_PERMISSION = "sales.discount";

  private static final java.util.Set<PaymentMethod> FOREIGN_REFUND_METHODS =
      java.util.EnumSet.of(PaymentMethod.CASH, PaymentMethod.CARD, PaymentMethod.MOBILE_MONEY);

  private final SaleRepository sales;
  private final SaleReturnRepository returns;
  private final SalesSettings settings;
  private final PaymentAuthorizations paymentAuthorizations;
  private final FiscalReceipts fiscalReceipts;
  private final DocumentNumbers documentNumbers;
  private final DomainEventPublisher events;
  private final tz.co.pharmacy.modules.sales.ports.outbound.SalesCurrencies currencies;
  private final VouchersImpl vouchers;
  private final TenantCalendar calendar;
  private final ShiftsImpl shifts;
  private final Approvals approvals;
  private final SalesProducts products;
  private final FiscalDocumentsImpl fiscal;

  /** The dispensing classes that never go back on the shelf once sold. */
  private static final Set<String> RESTRICTED = Set.of("PRESCRIPTION", "CONTROLLED");

  private static final Set<String> SCHEDULES = Set.of("OTC", "PRESCRIPTION", "CONTROLLED");

  @Override
  @Transactional
  public String nextSaleNumber() {
    UUID branch = ExecutionContext.requireBranch();
    return documentNumbers.format(settings.invoicePrefix() + "-" + settings.branchCode(branch), 6);
  }

  @Override
  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
  public void preparePayments(List<PaymentCommand> payments) {
    if (payments == null) {
      return;
    }
    for (int i = 0; i < payments.size(); i++) {
      PaymentCommand payment = payments.get(i);
      if (payment == null
          || payment.method() == null
          || payment.method() == PaymentMethod.CASH
          || payment.method() == PaymentMethod.VOUCHER
          || payment.reference() == null
          || payment.reference().isBlank()) {
        continue;
      }
      Optional<String> waiting =
          paymentAuthorizations.prepare(
              payment.method(), payment.channel(), payment.reference().trim());
      if (waiting.isPresent()) {
        throw Refusal.invalid("payment_declined", waiting.get(), "payments[" + i + "]");
      }
    }
  }

  @Override
  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
  public void fiscalise(UUID saleUid) {
    // STD-1: the receipt is a fiscal document, sent with a lease on it in transactions of its own.
    fiscal.issueReceiptOf(saleUid);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<SaleView> findByIdempotencyKey(String key) {
    if (key == null || key.isBlank()) {
      return Optional.empty();
    }
    return sales.findByIdempotencyKey(key.trim()).map(SaleServiceImpl::view);
  }

  @Override
  @Transactional(readOnly = true)
  public List<PlanSalesView> byPricingPlan(java.time.Instant from, java.time.Instant to) {
    java.util.Set<UUID> branches = ExecutionContext.reportBranches();
    List<Object[]> rows =
        branches == null
            ? sales.totalsByPlan(from, to)
            : branches.isEmpty() ? List.of() : sales.totalsByPlan(from, to, branches);
    return rows.stream()
        .map(
            r -> {
              BigDecimal total = r[2] == null ? BigDecimal.ZERO : (BigDecimal) r[2];
              BigDecimal normal = r[3] == null ? null : (BigDecimal) r[3];
              return new PlanSalesView(
                  r[0] == null ? "Normal Sale" : (String) r[0],
                  ((Number) r[1]).longValue(),
                  total,
                  normal,
                  normal == null ? null : normal.subtract(total));
            })
        .sorted(java.util.Comparator.comparing(PlanSalesView::total).reversed())
        .toList();
  }

  @Override
  @Transactional
  public Optional<SaleView> claimIdempotencyKey(String key) {
    return claimIdempotencyKey(key, null);
  }

  @Override
  @Transactional
  public Optional<SaleView> claimIdempotencyKey(String key, String requestFingerprint) {
    if (key == null || key.isBlank()) {
      return Optional.empty();
    }
    sales.holdLock("sale:" + ExecutionContext.requireTenant() + ":" + key.trim());
    Optional<Sale> made = sales.findByIdempotencyKey(key.trim());
    if (made.isPresent()
        && requestFingerprint != null
        && made.get().getRequestFingerprint() != null
        && !made.get().getRequestFingerprint().equals(requestFingerprint)) {
      throw Refusal.invalid(
              "idempotency_key_reused",
              "This idempotency key already made sale "
                  + made.get().getNumber()
                  + " from a different cart; a new sale needs a new key.",
              "idempotencyKey")
          .with("saleUid", made.get().getUid())
          .with("saleNumber", made.get().getNumber());
    }
    return made.map(SaleServiceImpl::view);
  }

  @Override
  @Transactional
  public SaleView record(RecordSaleCommand command) {
    UUID branch = ExecutionContext.requireBranch();
    String number = Checks.text(command.number(), "number", 40);
    List<SaleLineCommand> lineCommands = Checks.notEmpty(command.lines(), "lines");
    BigDecimal discountPercent = Checks.percent(command.discountPercent(), "discountPercent");
    shifts.requireOpenShift();
    if (discountPercent.signum() > 0) {
      if (!ExecutionContext.permits(DISCOUNT_PERMISSION)) {
        throw Refusal.invalid(
            "discount_not_allowed", "You may not give a discount.", "discountPercent");
      }
      BigDecimal max = settings.maxDiscountPercent();
      if (max != null && discountPercent.compareTo(max) > 0) {
        // FRD-8: deeper than the pharmacy's maximum, a manager approves it; the approval's amount
        // is the discount percent it covers (up to).
        approvals.require(ApprovalActions.DEEP_DISCOUNT, discountPercent, number);
      }
    }
    PrescriptionDetails prescription = command.prescription();
    boolean prescriptionRequired =
        lineCommands.stream().anyMatch(SaleLineCommand::prescriptionRequired);
    if (prescriptionRequired
        && (prescription == null
            || prescription.prescriberName() == null
            || prescription.prescriberName().isBlank())) {
      throw Refusal.invalid(
          "prescription_required",
          "A prescription item needs the prescriber's details.",
          "prescription");
    }
    if (prescriptionRequired) {
      requireFullPrescription(prescription);
    }

    Sale sale =
        new Sale(
            number,
            branch,
            settings.branchName(branch),
            command.customerUid(),
            command.customerName(),
            discountPercent,
            Checks.optionalText(command.idempotencyKey(), "idempotencyKey", 100),
            ExecutionContext.actor().name());
    sale.pricedUnder(command.priceListUid(), command.priceListName(), command.normalTotal());
    sale.fingerprinted(command.requestFingerprint());
    String planSource = command.pricePlanSource();
    sale.planFrom("CUSTOMER".equals(planSource) || "CHOSEN".equals(planSource) ? planSource : null);
    if (prescription != null && prescription.prescriberName() != null) {
      sale.prescribe(
          Checks.optionalText(prescription.prescriberName(), "prescription.prescriberName", 200),
          Checks.optionalText(
              prescription.prescriberRegistration(), "prescription.prescriberRegistration", 100),
          Checks.optionalText(prescription.reference(), "prescription.reference", 100),
          Checks.optionalText(prescription.patientName(), "prescription.patientName", 200),
          prescription.prescriptionDate());
    }
    if (prescriptionRequired) {
      // STD-7: the seller holds sales.dispense_rx (checkout refuses otherwise) and is the
      // dispenser.
      sale.dispensedBy(ExecutionContext.actor().name(), ExecutionContext.actor().uid());
    }

    List<SaleTotals.LineAmounts> amounts = new ArrayList<>();
    for (int i = 0; i < lineCommands.size(); i++) {
      SaleLineCommand line = lineCommands.get(i);
      String field = "lines[" + i + "]";
      Checks.required(line.productUid(), field + ".productUid");
      Checks.required(line.batchUid(), field + ".batchUid");
      int quantity = Checks.positive(line.quantity(), field + ".quantity");
      BigDecimal unitPrice = Checks.notNegative(line.unitPrice(), field + ".unitPrice");
      BigDecimal unitCost =
          line.unitCost() == null ? Amounts.ZERO : Checks.notNegative(line.unitCost(), "unitCost");
      BigDecimal taxRate = Checks.percent(line.taxRatePercent(), field + ".taxRatePercent");
      SaleTotals.LineAmounts lineAmounts =
          SaleTotals.line(unitPrice, quantity, discountPercent, taxRate, line.priceIncludesTax());
      amounts.add(lineAmounts);
      SaleLine saleLine =
          new SaleLine(
              line.productUid(),
              Checks.text(line.productName(), field + ".productName", 300),
              line.batchUid(),
              line.batchNumber(),
              line.expiryDate(),
              quantity,
              unitPrice,
              unitCost,
              taxRate,
              lineAmounts);
      saleLine.taxedAs(
          line.taxCategory() != null
              ? line.taxCategory()
              : tz.co.pharmacy.platform.kernel.TaxCategory.fromRate(taxRate));
      saleLine.classified(
          line.schedule() != null && SCHEDULES.contains(line.schedule()) ? line.schedule() : null,
          line.promotionUid(),
          Checks.optionalText(line.promotionName(), field + ".promotionName", 200));
      saleLine.sold(
          line.serialNumbers(),
          line.warrantyMonths() == null || line.warrantyMonths() <= 0
              ? null
              : calendar.today().plusMonths(line.warrantyMonths()).minusDays(1));
      sale.addLine(saleLine);
    }
    SaleTotals.Totals totals = SaleTotals.of(amounts);

    List<PaymentCommand> payments = command.payments() == null ? List.of() : command.payments();
    String base = currencies.baseCurrency();
    java.time.LocalDate today = calendar.today();
    List<tz.co.pharmacy.modules.sales.domain.Tender.Line> tenders = new java.util.ArrayList<>();
    for (int i = 0; i < payments.size(); i++) {
      PaymentCommand payment = payments.get(i);
      String field = "payments[" + i + "]";
      PaymentMethod method = Checks.required(payment.method(), field + ".method");
      if (payment.amount() == null || payment.amount().signum() <= 0) {
        throw Refusal.invalid(
            "invalid_amount", field + ".amount must be more than zero.", field + ".amount");
      }
      if ((method == PaymentMethod.CREDIT
              || method == PaymentMethod.INSURANCE
              || method == PaymentMethod.ACCOUNT_CREDIT)
          && command.customerUid() == null) {
        throw Refusal.invalid("customer_required", method + " needs a customer.", "customerUid");
      }
      String currency;
      BigDecimal rate;
      try {
        currency =
            payment.currency() == null || payment.currency().isBlank()
                ? base
                : currencies.accepted(payment.currency());
        if (payment.exchangeRate() != null && payment.exchangeRate().signum() <= 0) {
          throw Refusal.invalid(
              "invalid_exchange_rate", "exchangeRate must be more than zero.", "exchangeRate");
        }
        if (method == PaymentMethod.VOUCHER && !currency.equals(base)) {
          // A voucher's balance is held in the base currency; spending it in another one would
          // take the foreign figure off the balance.
          throw Refusal.invalid(
              "base_currency_only", "A voucher is spent in " + base + ".", "currency");
        }
        rate =
            currency.equals(base)
                ? BigDecimal.ONE
                : tillRate(currency, payment.exchangeRate(), today);
      } catch (Refusal refusal) {
        throw refusal.about(field + "." + (refusal.field() == null ? "currency" : refusal.field()));
      }
      tenders.add(
          tz.co.pharmacy.modules.sales.domain.Tender.line(
              method, currency, payment.amount(), rate, base, currencies::round));
    }
    tz.co.pharmacy.modules.sales.domain.Tender.Result tender =
        tz.co.pharmacy.modules.sales.domain.Tender.settle(tenders, totals.total());
    BigDecimal paid = tender.paid();
    BigDecimal changeDue = Amounts.money(tender.changeDue());

    for (int i = 0; i < payments.size(); i++) {
      PaymentCommand payment = payments.get(i);
      String reference =
          Checks.optionalText(payment.reference(), "payments[" + i + "].reference", 200);
      tz.co.pharmacy.modules.sales.domain.Tender.Line tenderLine = tenders.get(i);
      BigDecimal amount = tenderLine.amount();
      if (payment.method() == PaymentMethod.VOUCHER) {
        vouchers.spend(reference, amount, "payments[" + i + "].reference");
      } else if (payment.method() != PaymentMethod.CASH) {
        // A confirmed push payment whose excess checkout keeps on account is verified for the
        // amount the provider confirmed, not the part left on the sale.
        BigDecimal authorized =
            payment.authorizedAmount() != null && payment.authorizedAmount().compareTo(amount) > 0
                ? Amounts.money(payment.authorizedAmount(), tenderLine.currency())
                : amount;
        PaymentAuthorization authorization =
            paymentAuthorizations.authorize(
                payment.method(),
                payment.channel(),
                authorized,
                reference,
                number,
                sale.getIdempotencyKey());
        if (authorization == null || !authorization.approved()) {
          throw Refusal.invalid(
              "payment_declined",
              authorization == null || authorization.declineReason() == null
                  ? "The " + payment.method() + " payment was declined."
                  : authorization.declineReason(),
              "payments[" + i + "]");
        }
        reference = authorization.reference() == null ? reference : authorization.reference();
      }
      sale.addPayment(
          new SalePayment(
                  payment.method(),
                  amount,
                  reference,
                  tenderLine.currency(),
                  tenderLine.exchangeRate(),
                  tenderLine.baseAmount())
              .through(Checks.optionalText(payment.channel(), "payments[" + i + "].channel", 40)));
    }

    sale.totals(totals, Amounts.money(paid), changeDue);
    // PERF-1: the fiscal receipt is issued after commit (fiscalise), not while this transaction
    // holds the branch's sale number and the stock.
    sale.fiscalReceiptDue(fiscalReceipts.enabled());
    Sale saved = sales.save(sale);
    // STD-1: the receipt document, PENDING, written with the sale (only with a fiscal integration).
    List<tz.co.pharmacy.modules.sales.ports.inbound.FiscalDocumentView> receipt =
        fiscalReceipts.enabled() ? List.of(fiscal.view(fiscal.newReceipt(saved))) : List.of();

    events.publish(
        "sale.completed",
        metadata ->
            new SaleCompleted(
                metadata,
                saved.getUid(),
                saved.getNumber(),
                saved.getBranchUid(),
                saved.getCustomerUid(),
                saved.getSubtotal(),
                saved.getDiscountTotal(),
                saved.getTaxTotal(),
                saved.getTotal(),
                saved.getChangeDue(),
                saved.getLines().stream()
                    .map(
                        line ->
                            new SaleCompleted.Line(
                                line.getProductUid(),
                                line.getProductName(),
                                line.getQuantity(),
                                Amounts.money(line.getLineTotal().subtract(line.getTax())),
                                line.getTax(),
                                Amounts.times(line.getUnitCost(), line.getQuantity())))
                    .toList(),
                saved.getPayments().stream()
                    .map(
                        p ->
                            new SaleCompleted.Payment(
                                p.getMethod().name(),
                                p.getAmount(),
                                p.getCurrency(),
                                p.getExchangeRate(),
                                p.getBaseAmount()))
                    .toList()));
    return view(saved, receipt);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<SaleView> find(UUID uid) {
    return sales.findByUid(uid).map(sale -> view(sale, fiscal.documentsOf(sale.getUid())));
  }

  @Override
  @Transactional(readOnly = true)
  public Page<SaleSummaryView> list(SaleFilter filter, PageQuery query) {
    SaleFilter f = filter == null ? new SaleFilter(null, null, null, null) : filter;
    Specification<Sale> spec =
        Specs.<Sale>equal("branchUid", ExecutionContext.requireBranch())
            .and(Specs.containsAny(f.q(), "number", "customerName"))
            .and(Specs.equal("status", f.status()))
            .and(Specs.atLeast("createdAt", startOf(f.from())))
            .and(Specs.atLeast("createdAt", f.since()))
            .and(
                Specs.equal(
                    "idempotencyKey",
                    f.idempotencyKey() == null || f.idempotencyKey().isBlank()
                        ? null
                        : f.idempotencyKey().trim()))
            .and(Specs.before("createdAt", f.to() == null ? null : startOf(f.to().plusDays(1))));
    return Keyset.page(sales, spec, query, SaleServiceImpl::summary);
  }

  @Override
  @Transactional(readOnly = true)
  public List<SaleSummaryView> recent(Set<UUID> branchUids, int limit) {
    return Keyset.page(
            sales,
            Specs.in("branchUid", branchUids),
            PageQuery.first(Math.max(1, Math.min(limit, PageQuery.MAX_LIMIT))),
            SaleServiceImpl::summary)
        .items();
  }

  @Override
  @Transactional
  public SaleReturnView recordReturn(RecordReturnCommand command) {
    UUID branch = ExecutionContext.requireBranch();
    Sale sale =
        sales
            .findByUid(Checks.required(command.saleUid(), "saleUid"))
            .orElseThrow(() -> Refusal.notFound("sale_not_found", "No such sale."));
    if (!sale.getBranchUid().equals(branch)) {
      throw Refusal.invalid(
          "sale_other_branch", "A sale is returned at the branch that sold it.", "saleUid");
    }
    String reason = Checks.text(command.reason(), "reason", 500);
    PaymentMethod requested = command.refundMethod();
    List<ReturnLineCommand> lineCommands = Checks.notEmpty(command.lines(), "lines");
    shifts.requireOpenShift();
    List<SaleReturn> earlier = returns.findBySaleUidOrderByIdAsc(sale.getUid());
    java.util.Map<UUID, String> classesNow = classesNow(sale);

    SaleReturn saleReturn =
        new SaleReturn(
            documentNumbers.format("SRN-" + settings.branchCode(branch), 6),
            branch,
            sale.getUid(),
            sale.getNumber(),
            reason,
            requested == null ? PaymentMethod.CASH : requested);
    for (int i = 0; i < lineCommands.size(); i++) {
      ReturnLineCommand lineCommand = lineCommands.get(i);
      String field = "lines[" + i + "]";
      UUID saleLineUid = Checks.required(lineCommand.saleLineUid(), field + ".saleLineUid");
      int units = Checks.positive(lineCommand.quantity(), field + ".quantity");
      SaleLine line =
          sale.getLines().stream()
              .filter(candidate -> candidate.getUid().equals(saleLineUid))
              .findFirst()
              .orElseThrow(
                  () ->
                      Refusal.invalid(
                          "sale_line_not_found",
                          "The line is not on this sale.",
                          field + ".saleLineUid"));
      if (units > line.returnable()) {
        throw Refusal.invalid(
            "return_exceeds_sold",
            "Only " + line.returnable() + " of " + line.getProductName() + " may come back.",
            field + ".quantity");
      }
      List<String> returnedSerials = line.returnSerials(lineCommand.serialNumbers(), units);
      if (returnedSerials == null) {
        throw Refusal.invalid(
            "serial_not_on_sale",
            "Name " + units + " serial number(s) sold on this line and not yet returned.",
            field + ".serialNumbers");
      }
      line.returned(units);
      BigDecimal refund = SaleTotals.share(line.getLineTotal(), units, line.getQuantity());
      BigDecimal tax = SaleTotals.share(line.getTax(), units, line.getQuantity());
      if (line.returnable() == 0) {
        // The last units of a line take what is left of it, so the line's refunds add up to
        // exactly its total and tax: 10.00 over 3 units goes back as 3.33, 3.33 and 3.34.
        List<SaleReturnLine> before = new ArrayList<>(saleReturn.getLines());
        earlier.forEach(r -> before.addAll(r.getLines()));
        refund = Amounts.money(line.getLineTotal().subtract(refundedOf(before, line, true)));
        tax = Amounts.money(line.getTax().subtract(refundedOf(before, line, false)));
      }
      SaleReturnLine returnLine =
          new SaleReturnLine(
              line.getUid(),
              line.getProductUid(),
              line.getProductName(),
              line.getBatchUid(),
              line.getBatchNumber(),
              units,
              refund,
              tax,
              Amounts.times(line.getUnitCost(), units));
      returnLine.withSerials(returnedSerials);
      returnLine.dispose(
          disposition(line, lineCommand.disposition(), field + ".disposition", classesNow));
      saleReturn.addLine(returnLine);
    }
    List<RefundSplit.Share> shares = splitRefund(sale, earlier, saleReturn.getRefundTotal());
    saleReturn.refundedBy(
        shares.stream()
            .map(
                share ->
                    new SaleReturnRefund(
                        share.tender().method(), share.amount(), share.tender().reference()))
            .toList(),
        headline(requested, shares));
    refundInForeignCurrency(sale, saleReturn, command.refundCurrency(), shares);
    requireAllowedRefundMethod(sale, requested);
    requireApproval(sale, saleReturn.getRefundTotal());
    for (RefundSplit.Share share : shares) {
      if (share.tender().method() == PaymentMethod.VOUCHER && share.tender().reference() != null) {
        vouchers.creditBack(share.tender().reference(), share.amount());
      }
    }
    sale.refreshReturnStatus();
    // STD-1: a return of a sale with a fiscal receipt gets a credit note, issued after commit.
    boolean creditNote = fiscal.creditNoteFor(sale);
    if (creditNote) {
      saleReturn.creditNoteDue();
    }
    SaleReturn saved = returns.save(saleReturn);
    if (creditNote) {
      fiscal.newCreditNote(saved, sale);
    }

    events.publish(
        "sale.returned",
        metadata ->
            new SaleReturned(
                metadata,
                saved.getUid(),
                saved.getNumber(),
                saved.getSaleUid(),
                saved.getBranchUid(),
                saved.getRefundMethod().name(),
                saved.getRefundTotal(),
                saved.getLines().stream()
                    .map(
                        line ->
                            new SaleCompleted.Line(
                                line.getProductUid(),
                                line.getProductName(),
                                line.getQuantity(),
                                Amounts.money(line.getRefund().subtract(line.getTax())),
                                line.getTax(),
                                line.getCost()))
                    .toList(),
                saved.getRefundCurrency(),
                saved.getRefundForeignAmount(),
                saved.getFxDifference(),
                saved.getRefunds().stream()
                    .map(
                        refund ->
                            new SaleReturned.Refund(refund.getMethod().name(), refund.getAmount()))
                    .toList(),
                heldCost(saved)));
    return returnView(saved);
  }

  /** The cost of the units the return holds in quarantine, out of stock until released. */
  private static BigDecimal heldCost(SaleReturn saleReturn) {
    return saleReturn.getLines().stream()
        .filter(line -> line.getHeldQuantity() > 0)
        .map(SaleReturnLine::getCost)
        .reduce(Amounts.ZERO, BigDecimal::add);
  }

  /**
   * The owner's return rules (FRD-4): after the pharmacy's return window (days after the sale, on
   * its own calendar) a manager approves the return (LATE_RETURN, for its refund, so one approval
   * covers both); within it, a refund above the approval limit (REFUND).
   */
  private void requireApproval(Sale sale, BigDecimal refund) {
    LocalDate soldOn = LocalDate.ofInstant(sale.getCreatedAt(), calendar.zone());
    long days = java.time.temporal.ChronoUnit.DAYS.between(soldOn, calendar.today());
    if (days > settings.returnWindowDays()) {
      approvals.require(ApprovalActions.LATE_RETURN, refund, sale.getNumber());
    } else {
      approvals.require(ApprovalActions.REFUND, refund, sale.getNumber());
    }
  }

  /** The class now of products whose lines were sold before the class was kept on them. */
  private java.util.Map<UUID, String> classesNow(Sale sale) {
    List<UUID> unknown =
        sale.getLines().stream()
            .filter(line -> line.getSchedule() == null)
            .map(SaleLine::getProductUid)
            .distinct()
            .toList();
    return unknown.isEmpty() ? java.util.Map.of() : products.schedules(unknown);
  }

  /**
   * Where returned units go: as asked, else SHELF for an OTC medicine and QUARANTINE for a
   * prescription or controlled one, which never goes back on the shelf (422 restock_not_allowed).
   * The class is the one the medicine had when sold.
   */
  private static ReturnDisposition disposition(
      SaleLine line,
      ReturnDisposition asked,
      String field,
      java.util.Map<UUID, String> classesNow) {
    if (restricted(line, classesNow) && asked == ReturnDisposition.SHELF) {
      throw notOnShelf(line, classesNow, field);
    }
    if (asked != null) {
      return asked;
    }
    return restricted(line, classesNow) ? ReturnDisposition.QUARANTINE : ReturnDisposition.SHELF;
  }

  private static String scheduleOf(SaleLine line, java.util.Map<UUID, String> classesNow) {
    return line.getSchedule() != null ? line.getSchedule() : classesNow.get(line.getProductUid());
  }

  private static boolean restricted(SaleLine line, java.util.Map<UUID, String> classesNow) {
    String schedule = scheduleOf(line, classesNow);
    return schedule != null && RESTRICTED.contains(schedule);
  }

  private static Refusal notOnShelf(
      SaleLine line, java.util.Map<UUID, String> classesNow, String field) {
    return Refusal.invalid(
        "restock_not_allowed",
        line.getProductName()
            + " is a "
            + ("CONTROLLED".equals(scheduleOf(line, classesNow)) ? "controlled" : "prescription")
            + " medicine: once sold it never goes back on the shelf. Quarantine it or write it"
            + " off as damaged.",
        field);
  }

  @Override
  @Transactional
  public ReleasedLine releaseReturnLine(UUID returnUid, UUID lineUid, ReturnDisposition outcome) {
    UUID branch = ExecutionContext.requireBranch();
    ReturnDisposition to = Checks.required(outcome, "outcome");
    if (to == ReturnDisposition.QUARANTINE) {
      throw Refusal.invalid(
          "invalid_outcome", "Held units are released to SHELF or DAMAGED.", "outcome");
    }
    SaleReturn saleReturn =
        returns
            .lockByUid(returnUid)
            .orElseThrow(() -> Refusal.notFound("return_not_found", "No such return."));
    if (!saleReturn.getBranchUid().equals(branch)) {
      throw Refusal.invalid(
          "sale_other_branch", "A return is released at the branch that took it back.");
    }
    List<SaleReturnLine> lines = saleReturn.getLines();
    int index = -1;
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).getUid().equals(lineUid)) {
        index = i;
      }
    }
    if (index < 0) {
      throw Refusal.notFound("return_line_not_found", "The line is not on this return.");
    }
    SaleReturnLine line = lines.get(index);
    if (line.getHeldQuantity() <= 0) {
      throw Refusal.invalid("nothing_held", "Nothing on this line is held in quarantine.");
    }
    if (to == ReturnDisposition.SHELF) {
      Sale sale =
          sales
              .findByUid(saleReturn.getSaleUid())
              .orElseThrow(() -> Refusal.notFound("sale_not_found", "No such sale."));
      SaleLine sold =
          sale.getLines().stream()
              .filter(candidate -> candidate.getUid().equals(line.getSaleLineUid()))
              .findFirst()
              .orElseThrow(() -> Refusal.notFound("sale_line_not_found", "No such sale line."));
      java.util.Map<UUID, String> classesNow = classesNow(sale);
      if (restricted(sold, classesNow)) {
        throw notOnShelf(sold, classesNow, "outcome");
      }
    }
    int units = line.getHeldQuantity();
    String reference = saleReturn.getNumber() + "/Q" + (index + 1);
    line.release(
        to,
        tz.co.pharmacy.platform.kernel.Clocks.now(),
        ExecutionContext.actor().name(),
        ExecutionContext.actor().uid());
    events.publish(
        "sale.return_released",
        metadata ->
            new SaleReturnReleased(
                metadata,
                saleReturn.getUid(),
                line.getUid(),
                saleReturn.getNumber(),
                saleReturn.getBranchUid(),
                line.getProductUid(),
                line.getProductName(),
                units,
                line.getCost(),
                to.name(),
                reference));
    return new ReleasedLine(
        returnView(saleReturn),
        line.getProductUid(),
        line.getBatchUid(),
        units,
        line.getSerialNumbers(),
        reference,
        to);
  }

  @Override
  @Transactional(readOnly = true)
  public List<SaleReturnView> heldReturns() {
    return returns
        .findHeld(
            ExecutionContext.requireBranch(),
            org.springframework.data.domain.PageRequest.of(0, 200))
        .stream()
        .map(SaleServiceImpl::returnView)
        .toList();
  }

  /** What the earlier returns of the line refunded: their refunds, or their tax. */
  private static BigDecimal refundedOf(List<SaleReturnLine> lines, SaleLine line, boolean refund) {
    return lines.stream()
        .filter(earlier -> earlier.getSaleLineUid().equals(line.getUid()))
        .map(earlier -> refund ? earlier.getRefund() : earlier.getTax())
        .reduce(Amounts.ZERO, BigDecimal::add);
  }

  /**
   * The owner's rule: a refund goes back the way the sale was paid. Each tender gets its share (see
   * {@link RefundSplit}); a return from before the split counts as refunded by its method.
   */
  private static List<RefundSplit.Share> splitRefund(
      Sale sale, List<SaleReturn> earlier, BigDecimal refund) {
    java.util.Map<RefundSplit.Tender, BigDecimal> before = new java.util.LinkedHashMap<>();
    BigDecimal beforeTotal = Amounts.ZERO;
    for (SaleReturn r : earlier) {
      beforeTotal = beforeTotal.add(r.getRefundTotal());
      if (r.getRefunds().isEmpty()) {
        before.merge(
            new RefundSplit.Tender(r.getRefundMethod(), null), r.getRefundTotal(), BigDecimal::add);
      }
      for (SaleReturnRefund share : r.getRefunds()) {
        before.merge(
            new RefundSplit.Tender(share.getMethod(), share.getReference()),
            share.getAmount(),
            BigDecimal::add);
      }
    }
    boolean completes = sale.getLines().stream().allMatch(line -> line.returnable() == 0);
    return RefundSplit.split(
        RefundSplit.kept(sale), sale.getTotal(), before, beforeTotal, refund, completes);
  }

  /**
   * The method the return shows: the one the till named, else the largest share paid back to the
   * patient, else (a sale the insurer paid in full) INSURANCE.
   */
  private static PaymentMethod headline(PaymentMethod requested, List<RefundSplit.Share> shares) {
    if (requested != null) {
      return requested;
    }
    return shares.stream()
        .filter(share -> share.tender().method() != PaymentMethod.INSURANCE)
        .max(java.util.Comparator.comparing(RefundSplit.Share::amount))
        .or(() -> shares.stream().findFirst())
        .map(share -> share.tender().method())
        .orElse(PaymentMethod.CASH);
  }

  /**
   * A refund method the till names must be a way the sale was paid: never INSURANCE (the insurer
   * share comes off the claim; it is not the patient's money), the customer's account (CREDIT or
   * ACCOUNT_CREDIT) only for a customer's sale paid partly on or from it, a voucher only for a sale
   * paid by voucher, and cash, mobile money, card, bank transfer or cheque only when the sale was
   * paid so. 422 refund_method_not_allowed otherwise.
   */
  private static void requireAllowedRefundMethod(Sale sale, PaymentMethod requested) {
    if (requested == null) {
      return;
    }
    Set<PaymentMethod> paid = java.util.EnumSet.noneOf(PaymentMethod.class);
    sale.getPayments().forEach(payment -> paid.add(payment.getMethod()));
    boolean account =
        requested == PaymentMethod.CREDIT || requested == PaymentMethod.ACCOUNT_CREDIT;
    boolean allowed =
        requested != PaymentMethod.INSURANCE
            && (account
                ? sale.getCustomerUid() != null
                    && (paid.contains(PaymentMethod.CREDIT)
                        || paid.contains(PaymentMethod.ACCOUNT_CREDIT))
                : paid.contains(requested));
    if (allowed) {
      return;
    }
    String ways =
        paid.stream()
            .filter(method -> method != PaymentMethod.INSURANCE)
            .map(PaymentMethod::name)
            .collect(java.util.stream.Collectors.joining(", "));
    throw Refusal.invalid(
        "refund_method_not_allowed",
        (requested == PaymentMethod.INSURANCE
                ? "The insurer's share comes off the claim; it is never paid to the patient. "
                : "")
            + "A refund goes back the way the sale was paid"
            + (ways.isEmpty() ? "; leave refundMethod out." : " (" + ways + ")."),
        "refundMethod");
  }

  /**
   * A refund in a foreign currency the sale was tendered in: the customer gets back, in that
   * currency, what the returned goods cost them at the sale's rate; the till pays that out at
   * today's rate, and the difference from the booked base value is a realised exchange gain or
   * loss. Only cash-like refunds can be foreign: credit and insurance live in the base currency.
   */
  private void refundInForeignCurrency(
      Sale sale, SaleReturn saleReturn, String requested, List<RefundSplit.Share> shares) {
    if (requested == null || requested.isBlank()) {
      return;
    }
    String base = currencies.baseCurrency();
    String currency = requested.trim().toUpperCase();
    if (currency.equals(base)) {
      return;
    }
    if (!FOREIGN_REFUND_METHODS.contains(saleReturn.getRefundMethod())) {
      throw Refusal.invalid(
          "foreign_refund_method",
          "A refund in " + currency + " is paid in cash, by card or by mobile money.",
          "refundCurrency");
    }
    if (sale.getPayments().stream()
        .anyMatch(payment -> !FOREIGN_REFUND_METHODS.contains(payment.getMethod()))) {
      // Part of such a refund settles the customer's account or the insurer's claim, which are
      // kept in the base currency.
      throw Refusal.invalid(
          "foreign_refund_not_allowed",
          "A sale paid partly on credit, by insurance or from account credit is refunded in "
              + base
              + ".",
          "refundCurrency");
    }
    if (shares.stream().map(share -> share.tender().method()).distinct().count() > 1) {
      // The refund goes back by several methods; only one of them could be paid in the currency.
      throw Refusal.invalid(
          "foreign_refund_not_allowed",
          "A sale paid in several ways is refunded in " + base + ".",
          "refundCurrency");
    }
    BigDecimal saleRate =
        sale.getPayments().stream()
            .filter(payment -> currency.equals(payment.getCurrency()))
            .map(SalePayment::getExchangeRate)
            .findFirst()
            .orElseThrow(
                () ->
                    Refusal.invalid(
                        "refund_currency_not_tendered",
                        "The sale was not paid in " + currency + "; refund it in " + base + ".",
                        "refundCurrency"));
    BigDecimal foreign =
        currencies.round(
            saleReturn.getRefundTotal().divide(saleRate, 8, Amounts.ROUNDING), currency);
    BigDecimal todayRate = currencies.rateToBase(currency, calendar.today());
    saleReturn.refundIn(
        currency, foreign, saleRate, todayRate, Amounts.money(foreign.multiply(todayRate)));
  }

  @Override
  @Transactional(readOnly = true)
  public Page<SaleReturnView> returns(PageQuery query) {
    return Keyset.page(
        returns,
        Specs.equal("branchUid", ExecutionContext.requireBranch()),
        query,
        SaleServiceImpl::returnView);
  }

  /**
   * The start of the pharmacy's own day, in its time zone, as the ledger and the reports cut days:
   * a sale at 01:30 in Dar es Salaam (22:30 UTC the day before) is on the local day.
   */
  private Instant startOf(LocalDate date) {
    return date == null ? null : date.atStartOfDay(calendar.zone()).toInstant();
  }

  /**
   * STD-7: a prescription item is dispensed against a whole prescription: the prescriber's
   * registration number, the patient and a date neither after today nor older than {@link
   * PrescriptionDetails#VALID_MONTHS} months (422 prescription_incomplete,
   * prescription_date_in_future, prescription_expired). The prescriber's name is checked before it
   * (prescription_required).
   */
  private void requireFullPrescription(PrescriptionDetails rx) {
    if (blankText(rx.prescriberRegistration())) {
      throw incomplete("prescriberRegistration", "the prescriber's registration number");
    }
    if (blankText(rx.patientName())) {
      throw incomplete("patientName", "the patient's name");
    }
    if (rx.prescriptionDate() == null) {
      throw incomplete("prescriptionDate", "the prescription's date");
    }
    LocalDate today = calendar.today();
    if (rx.prescriptionDate().isAfter(today)) {
      throw Refusal.invalid(
          "prescription_date_in_future",
          "The prescription's date is after today.",
          "prescription.prescriptionDate");
    }
    LocalDate oldest = today.minusMonths(PrescriptionDetails.VALID_MONTHS);
    if (rx.prescriptionDate().isBefore(oldest)) {
      throw Refusal.invalid(
              "prescription_expired",
              "The prescription is older than "
                  + PrescriptionDetails.VALID_MONTHS
                  + " months; ask for a new one.",
              "prescription.prescriptionDate")
          .with("validMonths", PrescriptionDetails.VALID_MONTHS)
          .with("oldestAllowed", oldest.toString());
    }
  }

  private static Refusal incomplete(String field, String what) {
    return Refusal.invalid(
        "prescription_incomplete",
        "A prescription item needs " + what + ".",
        "prescription." + field);
  }

  private static boolean blankText(String value) {
    return value == null || value.isBlank();
  }

  static SaleView view(Sale sale) {
    return view(sale, List.of());
  }

  static SaleView view(
      Sale sale, List<tz.co.pharmacy.modules.sales.ports.inbound.FiscalDocumentView> documents) {
    PrescriptionDetails prescription =
        sale.getPrescriberName() == null
            ? null
            : new PrescriptionDetails(
                sale.getPrescriberName(),
                sale.getPrescriberRegistration(),
                sale.getPrescriptionReference(),
                sale.getPatientName(),
                sale.getPrescriptionDate());
    return new SaleView(
        sale.getUid(),
        sale.getNumber(),
        sale.getBranchUid(),
        sale.getBranchName(),
        sale.getCustomerUid(),
        sale.getCustomerName(),
        sale.getStatus(),
        sale.getDiscountPercent(),
        sale.getSubtotal(),
        sale.getDiscountTotal(),
        sale.getTaxTotal(),
        sale.getTotal(),
        sale.getPaidTotal(),
        sale.getChangeDue(),
        sale.getLines().stream()
            .map(
                line ->
                    new SaleLineView(
                        line.getUid(),
                        line.getProductUid(),
                        line.getProductName(),
                        line.getBatchUid(),
                        line.getBatchNumber(),
                        line.getExpiryDate(),
                        line.getQuantity(),
                        line.getReturnedQuantity(),
                        line.getUnitPrice(),
                        line.getDiscount(),
                        line.getTax(),
                        line.getLineTotal(),
                        line.serialNumbers(),
                        line.getWarrantyEndsOn(),
                        line.returnedSerialNumbers(),
                        line.getSchedule(),
                        line.getPromotionUid(),
                        line.getPromotionName(),
                        line.category(),
                        line.getTaxRatePercent()))
            .toList(),
        sale.getPayments().stream()
            .map(
                p ->
                    new PaymentView(
                        p.getMethod(),
                        p.getAmount(),
                        p.getReference(),
                        p.getCurrency(),
                        p.getExchangeRate().stripTrailingZeros(),
                        p.getBaseAmount(),
                        p.getChannel()))
            .toList(),
        prescription,
        sale.getFiscalReference(),
        sale.getCashierName(),
        sale.getCreatedAt(),
        sale.getPriceListUid(),
        sale.getPriceListName(),
        sale.getPricePlanSource(),
        sale.isFiscalDue(),
        sale.getFiscalStatus(),
        documents,
        sale.getDispensedBy(),
        false);
  }

  static SaleSummaryView summary(Sale sale) {
    return new SaleSummaryView(
        sale.getUid(),
        sale.getNumber(),
        sale.getCustomerName(),
        sale.getLines().stream().mapToInt(SaleLine::getQuantity).sum(),
        sale.getTotal(),
        sale.getPaidTotal(),
        sale.getPayments().stream().map(SalePayment::getMethod).distinct().toList(),
        sale.getStatus(),
        sale.getCashierName(),
        sale.getCreatedAt(),
        sale.getFiscalStatus());
  }

  static SaleReturnView returnView(SaleReturn saleReturn) {
    return new SaleReturnView(
        saleReturn.getUid(),
        saleReturn.getNumber(),
        saleReturn.getSaleUid(),
        saleReturn.getSaleNumber(),
        saleReturn.getReason(),
        saleReturn.getRefundMethod(),
        saleReturn.getRefundTotal(),
        saleReturn.getRefundCurrency(),
        saleReturn.getRefundForeignAmount(),
        saleReturn.getSaleExchangeRate(),
        saleReturn.getRefundExchangeRate(),
        saleReturn.getFxDifference(),
        saleReturn.getLines().stream()
            .map(
                line ->
                    new SaleReturnLineView(
                        line.getSaleLineUid(),
                        line.getProductUid(),
                        line.getProductName(),
                        line.getBatchUid(),
                        line.getBatchNumber(),
                        line.getQuantity(),
                        line.getRefund(),
                        line.getSerialNumbers(),
                        line.getUid(),
                        line.getDisposition(),
                        line.getHeldQuantity(),
                        line.getReleasedOutcome(),
                        line.getReleasedAt(),
                        line.getReleasedBy()))
            .toList(),
        saleReturn.getCreatedAt(),
        saleReturn.getCreatedBy(),
        saleReturn.getRefunds().isEmpty()
            ? List.of(
                new SaleRefundView(saleReturn.getRefundMethod(), saleReturn.getRefundTotal(), null))
            : saleReturn.getRefunds().stream()
                .map(r -> new SaleRefundView(r.getMethod(), r.getAmount(), r.getReference()))
                .toList(),
        saleReturn.getFiscalStatus(),
        saleReturn.getFiscalReference());
  }

  @Override
  @Transactional(readOnly = true)
  public tz.co.pharmacy.modules.sales.ports.inbound.SalePreview preview(
      List<SaleLineCommand> lines, BigDecimal discountPercent, List<PaymentCommand> payments) {
    BigDecimal discount = discountPercent == null ? BigDecimal.ZERO : discountPercent;
    List<SaleTotals.LineAmounts> amounts = new ArrayList<>();
    for (SaleLineCommand line : lines) {
      amounts.add(
          SaleTotals.line(
              line.unitPrice(),
              line.quantity(),
              discount,
              line.taxRatePercent() == null ? BigDecimal.ZERO : line.taxRatePercent(),
              line.priceIncludesTax()));
    }
    BigDecimal total = SaleTotals.of(amounts).total();
    String base = currencies.baseCurrency();
    java.time.LocalDate today = calendar.today();
    BigDecimal paid = BigDecimal.ZERO;
    BigDecimal cash = BigDecimal.ZERO;
    List<BigDecimal> baseAmounts = new ArrayList<>();
    for (PaymentCommand payment : payments == null ? List.<PaymentCommand>of() : payments) {
      if (payment.method() == null || payment.amount() == null || payment.amount().signum() <= 0) {
        baseAmounts.add(Amounts.ZERO);
        continue;
      }
      String currency =
          payment.currency() == null || payment.currency().isBlank()
              ? base
              : currencies.accepted(payment.currency());
      BigDecimal rate =
          currency.equals(base)
              ? BigDecimal.ONE
              : tillRate(currency, payment.exchangeRate(), today);
      BigDecimal value =
          tz.co.pharmacy.modules.sales.domain.Tender.line(
                  payment.method(), currency, payment.amount(), rate, base, currencies::round)
              .baseAmount();
      baseAmounts.add(value);
      paid = paid.add(value);
      if (payment.method() == PaymentMethod.CASH) {
        cash = cash.add(value);
      }
    }
    return new tz.co.pharmacy.modules.sales.ports.inbound.SalePreview(
        total,
        amounts.stream().map(SaleTotals.LineAmounts::lineTotal).toList(),
        Amounts.money(paid),
        Amounts.money(cash),
        List.copyOf(baseAmounts));
  }

  /** How far a rate the till sends may be from the recorded one (it may have been read earlier). */
  static final BigDecimal RATE_TOLERANCE = new BigDecimal("0.02");

  /**
   * The rate a foreign tender is valued at: always the pharmacy's recorded rate for the day, so a
   * cashier cannot set the value of the money they take in. The till may send the rate it showed;
   * one more than 2% from the recorded rate is refused ({@code exchange_rate_mismatch}) rather than
   * silently replaced, so the cashier sees that the change on screen was wrong.
   */
  private BigDecimal tillRate(String currency, BigDecimal sent, java.time.LocalDate today) {
    BigDecimal recorded = currencies.rateToBase(currency, today);
    if (sent != null && recorded != null && recorded.signum() > 0) {
      BigDecimal deviation =
          sent.subtract(recorded)
              .abs()
              .divide(recorded, 6, tz.co.pharmacy.platform.kernel.Amounts.ROUNDING);
      if (deviation.compareTo(RATE_TOLERANCE) > 0) {
        throw Refusal.invalid(
            "exchange_rate_mismatch",
            "The "
                + currency
                + " rate is "
                + recorded.stripTrailingZeros().toPlainString()
                + " today; a different rate is recorded under Exchange rates first.",
            "exchangeRate");
      }
    }
    return recorded;
  }
}
