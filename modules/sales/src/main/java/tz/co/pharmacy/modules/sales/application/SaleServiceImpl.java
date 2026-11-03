package tz.co.pharmacy.modules.sales.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.sales.domain.Sale;
import tz.co.pharmacy.modules.sales.domain.SaleLine;
import tz.co.pharmacy.modules.sales.domain.SalePayment;
import tz.co.pharmacy.modules.sales.domain.SaleReturn;
import tz.co.pharmacy.modules.sales.domain.SaleReturnLine;
import tz.co.pharmacy.modules.sales.domain.SaleTotals;
import tz.co.pharmacy.modules.sales.events.SaleCompleted;
import tz.co.pharmacy.modules.sales.events.SaleReturned;
import tz.co.pharmacy.modules.sales.persistence.SaleRepository;
import tz.co.pharmacy.modules.sales.persistence.SaleReturnRepository;
import tz.co.pharmacy.modules.sales.ports.inbound.PaymentCommand;
import tz.co.pharmacy.modules.sales.ports.inbound.PaymentMethod;
import tz.co.pharmacy.modules.sales.ports.inbound.PaymentView;
import tz.co.pharmacy.modules.sales.ports.inbound.PrescriptionDetails;
import tz.co.pharmacy.modules.sales.ports.inbound.RecordReturnCommand;
import tz.co.pharmacy.modules.sales.ports.inbound.RecordSaleCommand;
import tz.co.pharmacy.modules.sales.ports.inbound.ReturnLineCommand;
import tz.co.pharmacy.modules.sales.ports.inbound.SaleFilter;
import tz.co.pharmacy.modules.sales.ports.inbound.SaleLineCommand;
import tz.co.pharmacy.modules.sales.ports.inbound.SaleLineView;
import tz.co.pharmacy.modules.sales.ports.inbound.SaleReturnLineView;
import tz.co.pharmacy.modules.sales.ports.inbound.SaleReturnView;
import tz.co.pharmacy.modules.sales.ports.inbound.SaleService;
import tz.co.pharmacy.modules.sales.ports.inbound.SaleSummaryView;
import tz.co.pharmacy.modules.sales.ports.inbound.SaleView;
import tz.co.pharmacy.modules.sales.ports.outbound.FiscalReceipts;
import tz.co.pharmacy.modules.sales.ports.outbound.PaymentAuthorizations;
import tz.co.pharmacy.modules.sales.ports.outbound.PaymentAuthorizations.PaymentAuthorization;
import tz.co.pharmacy.modules.sales.ports.outbound.SalesSettings;
import tz.co.pharmacy.platform.bus.DomainEventPublisher;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;
import tz.co.pharmacy.platform.kernel.Refusal;
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

  @Override
  @Transactional
  public String nextSaleNumber() {
    UUID branch = ExecutionContext.requireBranch();
    return documentNumbers.format(settings.invoicePrefix() + "-" + settings.branchCode(branch), 6);
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
  @Transactional
  public SaleView record(RecordSaleCommand command) {
    UUID branch = ExecutionContext.requireBranch();
    String number = Checks.text(command.number(), "number", 40);
    List<SaleLineCommand> lineCommands = Checks.notEmpty(command.lines(), "lines");
    BigDecimal discountPercent = Checks.percent(command.discountPercent(), "discountPercent");
    if (discountPercent.signum() > 0) {
      if (!ExecutionContext.permits(DISCOUNT_PERMISSION)) {
        throw Refusal.invalid(
            "discount_not_allowed", "You may not give a discount.", "discountPercent");
      }
      BigDecimal max = settings.maxDiscountPercent();
      if (max != null && discountPercent.compareTo(max) > 0) {
        throw Refusal.invalid(
            "discount_not_allowed",
            "The discount may be at most " + max.stripTrailingZeros().toPlainString() + "%.",
            "discountPercent");
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
    if (prescription != null && prescription.prescriberName() != null) {
      sale.prescribe(
          Checks.optionalText(prescription.prescriberName(), "prescription.prescriberName", 200),
          Checks.optionalText(
              prescription.prescriberRegistration(), "prescription.prescriberRegistration", 100),
          Checks.optionalText(prescription.reference(), "prescription.reference", 100),
          Checks.optionalText(prescription.patientName(), "prescription.patientName", 200));
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
          SaleTotals.line(unitPrice, quantity, discountPercent, taxRate);
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
      saleLine.sold(
          line.serialNumbers(),
          line.warrantyMonths() == null || line.warrantyMonths() <= 0
              ? null
              : java.time.LocalDate.now().plusMonths(line.warrantyMonths()).minusDays(1));
      sale.addLine(saleLine);
    }
    SaleTotals.Totals totals = SaleTotals.of(amounts);

    List<PaymentCommand> payments = command.payments() == null ? List.of() : command.payments();
    String base = currencies.baseCurrency();
    java.time.LocalDate today = java.time.LocalDate.now();
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
        rate =
            currency.equals(base)
                ? BigDecimal.ONE
                : payment.exchangeRate() != null
                    ? payment.exchangeRate()
                    : currencies.rateToBase(currency, today);
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
      if (payment.method() != PaymentMethod.CASH) {
        PaymentAuthorization authorization =
            paymentAuthorizations.authorize(payment.method(), amount, reference, number);
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
              tenderLine.baseAmount()));
    }

    sale.totals(totals, Amounts.money(paid), changeDue);
    Sale saved = sales.save(sale);
    fiscalReceipts
        .issue(saved.getNumber(), totals.total(), totals.taxTotal())
        .ifPresent(saved::fiscalised);

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
    return view(saved);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<SaleView> find(UUID uid) {
    return sales.findByUid(uid).map(SaleServiceImpl::view);
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
    PaymentMethod refundMethod = Checks.required(command.refundMethod(), "refundMethod");
    List<ReturnLineCommand> lineCommands = Checks.notEmpty(command.lines(), "lines");

    SaleReturn saleReturn =
        new SaleReturn(
            documentNumbers.format("SRN-" + settings.branchCode(branch), 6),
            branch,
            sale.getUid(),
            sale.getNumber(),
            reason,
            refundMethod);
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
      SaleReturnLine returnLine =
          new SaleReturnLine(
              line.getUid(),
              line.getProductUid(),
              line.getProductName(),
              line.getBatchUid(),
              line.getBatchNumber(),
              units,
              SaleTotals.share(line.getLineTotal(), units, line.getQuantity()),
              SaleTotals.share(line.getTax(), units, line.getQuantity()),
              Amounts.times(line.getUnitCost(), units));
      returnLine.withSerials(returnedSerials);
      saleReturn.addLine(returnLine);
    }
    refundInForeignCurrency(sale, saleReturn, command.refundCurrency());
    sale.refreshReturnStatus();
    SaleReturn saved = returns.save(saleReturn);

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
                saved.getFxDifference()));
    return returnView(saved);
  }

  /**
   * A refund in a foreign currency the sale was tendered in: the customer gets back, in that
   * currency, what the returned goods cost them at the sale's rate; the till pays that out at
   * today's rate, and the difference from the booked base value is a realised exchange gain or
   * loss. Only cash-like refunds can be foreign: credit and insurance live in the base currency.
   */
  private void refundInForeignCurrency(Sale sale, SaleReturn saleReturn, String requested) {
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
            saleReturn.getRefundTotal().divide(saleRate, 8, RoundingMode.HALF_EVEN), currency);
    BigDecimal todayRate = currencies.rateToBase(currency, java.time.LocalDate.now());
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

  private static Instant startOf(LocalDate date) {
    return date == null ? null : date.atStartOfDay(ZoneOffset.UTC).toInstant();
  }

  static SaleView view(Sale sale) {
    PrescriptionDetails prescription =
        sale.getPrescriberName() == null
            ? null
            : new PrescriptionDetails(
                sale.getPrescriberName(),
                sale.getPrescriberRegistration(),
                sale.getPrescriptionReference(),
                sale.getPatientName());
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
                        line.returnedSerialNumbers()))
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
                        p.getBaseAmount()))
            .toList(),
        prescription,
        sale.getFiscalReference(),
        sale.getCashierName(),
        sale.getCreatedAt());
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
        sale.getCreatedAt());
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
                        line.getSerialNumbers()))
            .toList(),
        saleReturn.getCreatedAt(),
        saleReturn.getCreatedBy());
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
              line.taxRatePercent() == null ? BigDecimal.ZERO : line.taxRatePercent()));
    }
    BigDecimal total = SaleTotals.of(amounts).total();
    String base = currencies.baseCurrency();
    java.time.LocalDate today = java.time.LocalDate.now();
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
              : payment.exchangeRate() != null
                  ? payment.exchangeRate()
                  : currencies.rateToBase(currency, today);
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
}
