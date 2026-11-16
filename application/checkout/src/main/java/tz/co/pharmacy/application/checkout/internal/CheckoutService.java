package tz.co.pharmacy.application.checkout.internal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.application.checkout.ports.inbound.Checkout;
import tz.co.pharmacy.application.checkout.ports.inbound.CheckoutCommand;
import tz.co.pharmacy.application.checkout.ports.inbound.CheckoutLine;
import tz.co.pharmacy.application.checkout.ports.inbound.SellableProduct;
import tz.co.pharmacy.modules.catalogue.ports.inbound.CatalogueService;
import tz.co.pharmacy.modules.catalogue.ports.inbound.PackagingService;
import tz.co.pharmacy.modules.catalogue.ports.inbound.PricingService;
import tz.co.pharmacy.modules.catalogue.ports.inbound.ProductFilter;
import tz.co.pharmacy.modules.catalogue.ports.inbound.ProductView;
import tz.co.pharmacy.modules.catalogue.ports.inbound.Schedule;
import tz.co.pharmacy.modules.insurance.ports.inbound.ClaimCommand;
import tz.co.pharmacy.modules.insurance.ports.inbound.ClaimLineView;
import tz.co.pharmacy.modules.insurance.ports.inbound.CoverageLine;
import tz.co.pharmacy.modules.insurance.ports.inbound.CoverageLineQuote;
import tz.co.pharmacy.modules.insurance.ports.inbound.CoverageQuote;
import tz.co.pharmacy.modules.insurance.ports.inbound.Insurance;
import tz.co.pharmacy.modules.inventory.ports.inbound.Allocation;
import tz.co.pharmacy.modules.inventory.ports.inbound.StockQueries;
import tz.co.pharmacy.modules.inventory.ports.inbound.StockService;
import tz.co.pharmacy.modules.inventory.ports.inbound.TransformationType;
import tz.co.pharmacy.modules.inventory.ports.inbound.Transformations;
import tz.co.pharmacy.modules.partners.ports.inbound.CustomerService;
import tz.co.pharmacy.modules.receivables.ports.inbound.CustomerCredit;
import tz.co.pharmacy.modules.receivables.ports.inbound.ReceivePaymentCommand;
import tz.co.pharmacy.modules.sales.ports.inbound.PaymentCommand;
import tz.co.pharmacy.modules.sales.ports.inbound.PaymentMethod;
import tz.co.pharmacy.modules.sales.ports.inbound.PrescriptionDetails;
import tz.co.pharmacy.modules.sales.ports.inbound.RecordReturnCommand;
import tz.co.pharmacy.modules.sales.ports.inbound.RecordSaleCommand;
import tz.co.pharmacy.modules.sales.ports.inbound.SaleLineCommand;
import tz.co.pharmacy.modules.sales.ports.inbound.SalePreview;
import tz.co.pharmacy.modules.sales.ports.inbound.SaleReturnLineView;
import tz.co.pharmacy.modules.sales.ports.inbound.SaleReturnView;
import tz.co.pharmacy.modules.sales.ports.inbound.SaleService;
import tz.co.pharmacy.modules.sales.ports.inbound.SaleView;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.kernel.TenantCalendar;

/**
 * Each use case runs in the one transaction it opens; the modules' write methods join it, so a
 * refusal from any of them, such as {@code insufficient_stock} on the third line or {@code
 * payment_short}, leaves none of the stock taken and no sale.
 */
@Service
@RequiredArgsConstructor
class CheckoutService implements Checkout {

  private final CatalogueService catalogue;
  private final StockService stock;
  private final StockQueries stockQueries;
  private final CustomerService customers;
  private final SaleService sales;
  private final Insurance insurance;
  private final CustomerCredit credit;
  private final PricingService pricing;
  private final PackagingService packaging;
  private final Transformations transformations;
  private final TenantCalendar calendar;

  /** The permission to sell on account: credit sales and balances put on account. */
  static final String CREDIT_PERMISSION = "sales.credit";

  @Override
  @Transactional
  public SaleView checkout(CheckoutCommand command) {
    String key = Checks.optionalText(command.idempotencyKey(), "idempotencyKey", 100);
    if (key != null) {
      var existing = sales.findByIdempotencyKey(key);
      if (existing.isPresent()) {
        return existing.get();
      }
    }
    List<CheckoutLine> lines = Checks.notEmpty(command.lines(), "lines");
    for (int i = 0; i < lines.size(); i++) {
      Checks.required(lines.get(i).productUid(), "lines[" + i + "].productUid");
      Checks.positive(lines.get(i).quantity(), "lines[" + i + "].quantity");
    }
    Map<UUID, ProductView> products =
        catalogue
            .products(lines.stream().map(CheckoutLine::productUid).distinct().toList())
            .stream()
            .collect(Collectors.toMap(ProductView::uid, Function.identity()));

    String customerName = null;
    String customerType = null;
    if (command.customerUid() != null) {
      var customer =
          customers
              .find(command.customerUid())
              .orElseThrow(
                  () -> Refusal.invalid("customer_not_found", "No such customer.", "customerUid"));
      customerName = customer.name();
      customerType = customer.type() == null ? null : customer.type().name();
    }
    // The price of each line for this customer here and now: branch price, the customer's or their
    // type's price list (with quantity breaks), then the best running promotion.
    Map<UUID, Integer> quantities = new java.util.LinkedHashMap<>();
    lines.forEach(l -> quantities.merge(l.productUid(), l.quantity(), Integer::sum));
    Map<UUID, BigDecimal> prices =
        pricing
            .price(
                ExecutionContext.requireBranch(),
                customerType,
                command.customerUid(),
                quantities.entrySet().stream()
                    .filter(e -> products.containsKey(e.getKey()))
                    .map(e -> new PricingService.PriceRequest(e.getKey(), e.getValue()))
                    .toList(),
                calendar.today())
            .stream()
            .collect(
                Collectors.toMap(
                    PricingService.PricedLine::productUid, PricingService.PricedLine::unitPrice));

    String number = sales.nextSaleNumber();
    List<SaleLineCommand> saleLines = new ArrayList<>();
    for (int i = 0; i < lines.size(); i++) {
      CheckoutLine line = lines.get(i);
      String field = "lines[" + i + "]";
      ProductView product = products.get(line.productUid());
      if (product == null) {
        throw Refusal.invalid("product_not_found", "No such product.", field + ".productUid");
      }
      if (!product.active()) {
        throw Refusal.invalid(
            "product_inactive", product.name() + " is no longer sold.", field + ".productUid");
      }
      List<String> serials =
          line.serialNumbers() == null
              ? List.of()
              : line.serialNumbers().stream()
                  .filter(serial -> serial != null && !serial.isBlank())
                  .map(String::trim)
                  .toList();
      if (product.requiresSerialAtSale() && serials.isEmpty()) {
        throw Refusal.invalid(
            "serial_required",
            "Scan or enter the serial number of each " + product.name() + " sold.",
            field + ".serialNumbers");
      }
      if (serials.isEmpty()) {
        openPacksFor(product.uid(), line.quantity(), number);
      }
      List<Allocation> allocations;
      try {
        allocations = stock.allocate(product.uid(), line.quantity(), number, serials);
      } catch (Refusal refusal) {
        throw refusal.field() == null
            ? refusal.about(field + ".quantity")
            : refusal.field().startsWith("lines[")
                ? refusal
                : refusal.about(field + "." + refusal.field());
      }
      BigDecimal taxRate =
          product.taxRatePercent() == null ? BigDecimal.ZERO : product.taxRatePercent();
      for (Allocation allocation : allocations) {
        saleLines.add(
            new SaleLineCommand(
                product.uid(),
                product.name(),
                product.schedule() != null && product.schedule() != Schedule.OTC,
                allocation.batchUid(),
                allocation.batchNumber(),
                allocation.expiryDate(),
                allocation.quantity(),
                prices.getOrDefault(product.uid(), product.sellingPrice()),
                allocation.unitCost(),
                taxRate,
                allocation.serialNumbers(),
                product.warrantyMonths()));
      }
    }
    requireControlledPrescription(command, products);
    return settle(command, number, key, customerName, saleLines, products);
  }

  /**
   * A CONTROLLED item is entered in the controlled-drugs register against a full prescription: the
   * prescriber's registration number, the prescription reference and the patient, not only the
   * prescriber's name that a PRESCRIPTION item needs.
   */
  private static void requireControlledPrescription(
      CheckoutCommand command, Map<UUID, ProductView> products) {
    boolean controlled =
        products.values().stream().anyMatch(p -> p.schedule() == Schedule.CONTROLLED);
    if (!controlled) {
      return;
    }
    PrescriptionDetails rx = command.prescription();
    if (rx == null
        || blank(rx.prescriberName())
        || blank(rx.prescriberRegistration())
        || blank(rx.reference())
        || blank(rx.patientName())) {
      throw Refusal.invalid(
          "controlled_prescription_incomplete",
          "A controlled drug needs the prescriber's name and registration number, the"
              + " prescription reference and the patient's name.",
          "prescription");
    }
  }

  /**
   * Break bulk at the till: when too few loose units are on hand and the product comes in a pack (a
   * strip from a box), opens just enough packs first. If even the packs fall short the sale reports
   * the unit's shortage as usual.
   */
  private void openPacksFor(UUID productUid, int quantity, String saleNumber) {
    packaging
        .packOf(productUid)
        .ifPresent(
            link -> {
              int onHand = stockQueries.onHand(List.of(productUid)).getOrDefault(productUid, 0);
              if (onHand >= quantity) {
                return;
              }
              int packs = (quantity - onHand + link.unitsPerPack() - 1) / link.unitsPerPack();
              try {
                transformations.transform(
                    new Transformations.TransformCommand(
                        TransformationType.BREAK_BULK,
                        List.of(new Transformations.InputCommand(link.packProductUid(), packs)),
                        new Transformations.OutputCommand(
                            productUid, packs * link.unitsPerPack(), null, null),
                        "Opened at the till for " + saleNumber));
              } catch (Refusal notEnoughPacks) {
                // Fall through: allocation below refuses with the unit's own shortage.
              }
            });
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  /**
   * Records the sale with its tenders settled: the insurer's covered share as an INSURANCE payment,
   * a balance or a non-cash excess on the customer's account when asked, then the account charge,
   * the account credit spent and the claim, all in the sale's transaction.
   */
  private SaleView settle(
      CheckoutCommand command,
      String number,
      String key,
      String customerName,
      List<SaleLineCommand> saleLines,
      Map<UUID, ProductView> products) {
    UUID customer = command.customerUid();
    List<PaymentCommand> payments =
        new ArrayList<>(command.payments() == null ? List.of() : command.payments());
    boolean balanceToAccount = Boolean.TRUE.equals(command.balanceToAccount());
    boolean excessToAccount = Boolean.TRUE.equals(command.excessToAccount());
    boolean onAccount =
        balanceToAccount
            || excessToAccount
            || payments.stream().anyMatch(p -> p.method() == PaymentMethod.CREDIT);
    boolean spendsCredit =
        payments.stream().anyMatch(p -> p.method() == PaymentMethod.ACCOUNT_CREDIT);
    if ((onAccount || spendsCredit) && customer == null) {
      throw Refusal.invalid(
          "customer_required",
          "A walk-in customer cannot owe; choose the customer.",
          "customerUid");
    }
    if (onAccount && !ExecutionContext.permits(CREDIT_PERMISSION)) {
      throw Refusal.forbidden(
          "credit_not_permitted", "Your role may not sell on account (sales.credit).");
    }
    for (int i = 0; i < payments.size(); i++) {
      PaymentCommand payment = payments.get(i);
      if (onAccountMethod(payment.method())
          && payment.currency() != null
          && !payment.currency().isBlank()) {
        SalePreview check = sales.preview(saleLines, command.discountPercent(), List.of(payment));
        if (check.paymentBaseAmounts().getFirst().compareTo(Amounts.money(payment.amount())) != 0) {
          throw Refusal.invalid(
              "base_currency_only",
              payment.method() + " is settled in the base currency.",
              "payments[" + i + "].currency");
        }
      }
    }

    // Scenario 4: the insurer's share, priced exactly as the sale will be, split by the scheme.
    CoverageQuote quote = null;
    if (command.memberUid() != null) {
      if (customer == null) {
        throw Refusal.invalid(
            "customer_required", "An insured sale needs the member's customer.", "customerUid");
      }
      SalePreview priced = sales.preview(saleLines, command.discountPercent(), List.of());
      quote =
          insurance.quote(
              command.memberUid(), coverage(saleLines, priced, products), command.preauthNumber());
      BigDecimal insured =
          payments.stream()
              .filter(p -> p.method() == PaymentMethod.INSURANCE)
              .map(p -> Amounts.money(p.amount()))
              .reduce(Amounts.ZERO, BigDecimal::add);
      boolean sent = payments.stream().anyMatch(p -> p.method() == PaymentMethod.INSURANCE);
      if (!sent && quote.covered().signum() > 0) {
        payments.add(0, new PaymentCommand(PaymentMethod.INSURANCE, quote.covered(), null));
      } else if (sent && insured.compareTo(quote.covered()) != 0) {
        throw Refusal.invalid(
            "insurance_amount_mismatch",
            "The insurer covers "
                + quote.covered().toPlainString()
                + "; the patient pays "
                + quote.patientPays().toPlainString()
                + ".",
            "payments");
      }
    } else if (payments.stream().anyMatch(p -> p.method() == PaymentMethod.INSURANCE)) {
      throw Refusal.invalid(
          "member_required", "An insurance payment needs the customer's membership.", "memberUid");
    }

    // Scenarios 1 and 7: the balance, or a non-cash excess, goes on the customer's account.
    BigDecimal deposit = Amounts.ZERO;
    PaymentMethod depositMethod = null;
    SalePreview preview = sales.preview(saleLines, command.discountPercent(), payments);
    BigDecimal shortfall = Amounts.money(preview.total().subtract(preview.paid()));
    if (balanceToAccount && shortfall.signum() > 0) {
      payments.add(new PaymentCommand(PaymentMethod.CREDIT, shortfall, "Balance on account"));
    }
    BigDecimal excess = Amounts.money(preview.paid().subtract(preview.total()));
    BigDecimal overNonCash = Amounts.money(excess.subtract(preview.cashPaid()));
    if (excessToAccount && overNonCash.signum() > 0) {
      int index = lastTrimmable(payments, preview, overNonCash);
      if (index < 0) {
        throw Refusal.invalid(
            "overpaid_non_cash",
            "Only a base-currency card or mobile money payment can leave its excess on account.",
            "payments");
      }
      PaymentCommand trimmed = payments.get(index);
      deposit = overNonCash;
      depositMethod = trimmed.method();
      payments.set(
          index,
          new PaymentCommand(
              trimmed.method(),
              Amounts.money(trimmed.amount().subtract(overNonCash)),
              trimmed.reference(),
              trimmed.currency(),
              trimmed.exchangeRate(),
              trimmed.channel()));
    }

    SaleView sale =
        sales.record(
            new RecordSaleCommand(
                number,
                key,
                customer,
                customerName,
                command.discountPercent(),
                saleLines,
                payments,
                command.prescription()));

    BigDecimal onCredit = paidBy(sale, PaymentMethod.CREDIT);
    BigDecimal fromAccount = paidBy(sale, PaymentMethod.ACCOUNT_CREDIT);
    if (onCredit.signum() > 0) {
      try {
        credit.checkCredit(customer, onCredit);
      } catch (Refusal refusal) {
        throw refusal.field() == null ? refusal.about("payments") : refusal;
      }
      credit.charge(customer, sale.uid(), sale.number(), onCredit, null);
    }
    if (fromAccount.signum() > 0) {
      credit.applyCredit(customer, sale.uid(), sale.number(), fromAccount);
    }
    if (deposit.signum() > 0) {
      credit.receivePayment(
          new ReceivePaymentCommand(
              customer,
              deposit,
              depositMethod.name(),
              "Excess on " + sale.number(),
              null,
              List.of(),
              null,
              null));
    }
    if (quote != null && quote.covered().signum() > 0) {
      insurance.createClaim(
          new ClaimCommand(
              command.memberUid(),
              sale.uid(),
              sale.number(),
              customerName,
              command.preauthNumber(),
              command.diagnosisCode(),
              claimLines(quote, saleLines, products)));
    }
    return sale;
  }

  private static boolean onAccountMethod(PaymentMethod method) {
    return method == PaymentMethod.CREDIT
        || method == PaymentMethod.ACCOUNT_CREDIT
        || method == PaymentMethod.INSURANCE;
  }

  /** The last base-currency card or mobile money payment large enough to give up the excess. */
  private static int lastTrimmable(
      List<PaymentCommand> payments, SalePreview preview, BigDecimal excess) {
    for (int i = payments.size() - 1; i >= 0; i--) {
      PaymentCommand payment = payments.get(i);
      boolean base =
          payment.currency() == null
              || payment.currency().isBlank()
              || preview.paymentBaseAmounts().get(i).compareTo(Amounts.money(payment.amount()))
                  == 0;
      if ((payment.method() == PaymentMethod.CARD || payment.method() == PaymentMethod.MOBILE_MONEY)
          && base
          && Amounts.money(payment.amount()).compareTo(excess) > 0) {
        return i;
      }
    }
    return -1;
  }

  /** One coverage line per product, at its total as the sale prices it. */
  private static List<CoverageLine> coverage(
      List<SaleLineCommand> saleLines, SalePreview priced, Map<UUID, ProductView> products) {
    Map<UUID, BigDecimal> totals = new java.util.LinkedHashMap<>();
    for (int i = 0; i < saleLines.size(); i++) {
      totals.merge(saleLines.get(i).productUid(), priced.lineTotals().get(i), BigDecimal::add);
    }
    List<CoverageLine> lines = new ArrayList<>();
    totals.forEach(
        (product, total) -> {
          ProductView view = products.get(product);
          Object type = view == null ? null : view.productType();
          lines.add(new CoverageLine(product, type == null ? null : type.toString(), total));
        });
    return lines;
  }

  /** The claim's lines: the insurer's share of each covered product. */
  private static List<ClaimLineView> claimLines(
      CoverageQuote quote, List<SaleLineCommand> saleLines, Map<UUID, ProductView> products) {
    Map<UUID, Integer> quantities = new java.util.HashMap<>();
    saleLines.forEach(line -> quantities.merge(line.productUid(), line.quantity(), Integer::sum));
    List<ClaimLineView> lines = new ArrayList<>();
    for (CoverageLineQuote line : quote.lines()) {
      if (line.covered().signum() <= 0) {
        continue;
      }
      ProductView product = products.get(line.productUid());
      lines.add(
          new ClaimLineView(
              line.productUid(),
              product == null ? null : product.name(),
              quantities.getOrDefault(line.productUid(), 0),
              line.covered(),
              null,
              null));
    }
    return lines;
  }

  private static BigDecimal paidBy(SaleView sale, PaymentMethod method) {
    return sale.payments().stream()
        .filter(p -> p.method() == method)
        .map(p -> p.baseAmount() == null ? p.amount() : p.baseAmount())
        .reduce(Amounts.ZERO, BigDecimal::add);
  }

  @Override
  @Transactional
  public SaleReturnView returnSale(RecordReturnCommand command) {
    SaleReturnView saleReturn = sales.recordReturn(command);
    for (SaleReturnLineView line : saleReturn.lines()) {
      stock.restore(line.batchUid(), line.quantity(), saleReturn.number(), line.serialNumbers());
    }
    sales.find(saleReturn.saleUid()).ifPresent(sale -> settleReturn(sale, saleReturn));
    return saleReturn;
  }

  /**
   * Scenario 8. The refund splits like the sale was paid: the insurer's share comes off the claim;
   * on a credit sale the patient's share first settles the sale's open charge as a credit note, and
   * the rest is refunded by the chosen method, or kept on account when that method is CREDIT or
   * ACCOUNT_CREDIT.
   */
  private void settleReturn(SaleView sale, SaleReturnView saleReturn) {
    BigDecimal refund = saleReturn.refundTotal();
    if (refund == null || refund.signum() <= 0 || sale.total().signum() <= 0) {
      return;
    }
    String method = saleReturn.refundMethod() == null ? null : saleReturn.refundMethod().name();
    BigDecimal insured = paidBy(sale, PaymentMethod.INSURANCE);
    BigDecimal insurerShare = Amounts.ZERO;
    if (insured.signum() > 0) {
      insurerShare =
          Amounts.money(
              refund
                  .multiply(insured)
                  .divide(sale.total(), 6, java.math.RoundingMode.HALF_UP)
                  .min(refund));
      insurance.reduceForReturn(sale.uid(), insurerShare, saleReturn.number(), method);
    }
    BigDecimal patientShare = Amounts.money(refund.subtract(insurerShare));
    if (sale.customerUid() == null || patientShare.signum() <= 0) {
      return;
    }
    boolean toAccount =
        saleReturn.refundMethod() == PaymentMethod.CREDIT
            || saleReturn.refundMethod() == PaymentMethod.ACCOUNT_CREDIT;
    BigDecimal open =
        paidBy(sale, PaymentMethod.CREDIT).signum() > 0
            ? credit.openForSale(sale.customerUid(), sale.uid())
            : Amounts.ZERO;
    BigDecimal note = toAccount ? patientShare : Amounts.money(patientShare.min(open));
    if (note.signum() > 0) {
      credit.creditNote(sale.customerUid(), sale.uid(), saleReturn.number(), note, method);
    }
  }

  @Override
  @Transactional(readOnly = true)
  public CoverageQuote coverageQuote(
      UUID memberUid, List<CheckoutLine> lines, String preauthNumber) {
    List<CheckoutLine> basket = Checks.notEmpty(lines, "lines");
    Map<UUID, ProductView> products =
        catalogue
            .products(basket.stream().map(CheckoutLine::productUid).distinct().toList())
            .stream()
            .collect(Collectors.toMap(ProductView::uid, Function.identity()));
    List<SaleLineCommand> priced = new ArrayList<>();
    for (int i = 0; i < basket.size(); i++) {
      ProductView product = products.get(basket.get(i).productUid());
      if (product == null) {
        throw Refusal.invalid(
            "product_not_found", "No such product.", "lines[" + i + "].productUid");
      }
      priced.add(
          new SaleLineCommand(
              product.uid(),
              product.name(),
              false,
              null,
              null,
              null,
              Checks.positive(basket.get(i).quantity(), "lines[" + i + "].quantity"),
              product.sellingPrice(),
              null,
              product.taxRatePercent() == null ? BigDecimal.ZERO : product.taxRatePercent(),
              null,
              null));
    }
    SalePreview preview = sales.preview(priced, BigDecimal.ZERO, List.of());
    return insurance.quote(memberUid, coverage(priced, preview, products), preauthNumber);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<SellableProduct> sellable(String q, UUID categoryUid, PageQuery query) {
    Page<ProductView> page =
        catalogue.products(new ProductFilter(q, categoryUid, true, null), query);
    List<UUID> uids = page.items().stream().map(ProductView::uid).toList();
    Map<UUID, Integer> onHand = stockQueries.onHand(uids);
    // Units a cashier can still sell by opening packs here (break bulk at the till).
    Map<UUID, PackagingService.PackLinkView> packs = packaging.packsOf(uids);
    Map<UUID, Integer> packsOnHand =
        packs.isEmpty()
            ? Map.of()
            : stockQueries.onHand(
                packs.values().stream()
                    .map(PackagingService.PackLinkView::packProductUid)
                    .distinct()
                    .toList());
    return page.map(
        product ->
            new SellableProduct(
                product.uid(),
                product.name(),
                product.genericName(),
                product.sku(),
                product.gtin(),
                product.dosageForm(),
                product.strength(),
                product.categoryUid(),
                product.categoryName(),
                product.schedule(),
                product.sellingPrice(),
                product.taxRatePercent(),
                onHand.getOrDefault(product.uid(), 0),
                product.productType(),
                product.trackingMode(),
                product.unitOfMeasure(),
                product.requiresSerialAtSale(),
                openable(packs.get(product.uid()), packsOnHand)));
  }

  private static int openable(PackagingService.PackLinkView pack, Map<UUID, Integer> packsOnHand) {
    if (pack == null) {
      return 0;
    }
    return packsOnHand.getOrDefault(pack.packProductUid(), 0) * pack.unitsPerPack();
  }
}
