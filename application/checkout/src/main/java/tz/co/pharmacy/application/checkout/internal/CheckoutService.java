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
import tz.co.pharmacy.modules.inventory.ports.inbound.AdjustStockCommand;
import tz.co.pharmacy.modules.inventory.ports.inbound.AdjustmentReason;
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
import tz.co.pharmacy.modules.sales.ports.inbound.ReturnDisposition;
import tz.co.pharmacy.modules.sales.ports.inbound.SaleLineCommand;
import tz.co.pharmacy.modules.sales.ports.inbound.SalePreview;
import tz.co.pharmacy.modules.sales.ports.inbound.SaleReturnLineView;
import tz.co.pharmacy.modules.sales.ports.inbound.SaleReturnView;
import tz.co.pharmacy.modules.sales.ports.inbound.SaleService;
import tz.co.pharmacy.modules.sales.ports.inbound.SaleView;
import tz.co.pharmacy.modules.tenancy.ports.inbound.Entitlements;
import tz.co.pharmacy.modules.tenancy.ports.inbound.PlanFeature;
import tz.co.pharmacy.modules.tenancy.ports.inbound.SettingsService;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.kernel.TenantCalendar;

/**
 * Each use case runs in one transaction; the modules' write methods join it, so a refusal from any
 * of them, such as {@code insufficient_stock} on the third line or {@code payment_short}, leaves
 * none of the stock taken and no sale.
 *
 * <p>A checkout keeps every call out of that transaction (PERF-1). The branch's sale number is a
 * row lock held until commit (numbering is gapless), and the products' stock locks are held as
 * long, so a slow payment provider or tax authority would hold every other till of the branch. The
 * payment providers are asked first, outside any transaction ({@link SaleService#preparePayments});
 * then one transaction takes the idempotency key, the stock locks, the number, the stock and the
 * sale, all database work; the fiscal receipt is issued after commit by the {@code sale.completed}
 * reaction ({@link SaleService#fiscalise}).
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
  private final SettingsService settings;
  private final Entitlements entitlements;
  private final TenantCalendar calendar;
  private final org.springframework.transaction.PlatformTransactionManager transactionManager;

  /** The permission to sell on account: credit sales and balances put on account. */
  static final String CREDIT_PERMISSION = "sales.credit";

  /**
   * Not {@code @Transactional}: the payment providers are asked before the sale's transaction
   * starts, and that transaction is the template's below.
   */
  @Override
  public SaleView checkout(CheckoutCommand command) {
    String key = Checks.optionalText(command.idempotencyKey(), "idempotencyKey", 100);
    List<CheckoutLine> lines = Checks.notEmpty(command.lines(), "lines");
    for (int i = 0; i < lines.size(); i++) {
      Checks.required(lines.get(i).productUid(), "lines[" + i + "].productUid");
      Checks.positive(lines.get(i).quantity(), "lines[" + i + "].quantity");
    }
    // Outside any transaction: a push request still pending is asked about now, so nothing below
    // waits on a provider while it holds the number and the stock.
    sales.preparePayments(command.payments());
    // WEB-2: the key answers again only for the same request; another cart under it is refused.
    String fingerprint = key == null ? null : SaleFingerprint.of(command);
    return new org.springframework.transaction.support.TransactionTemplate(transactionManager)
        .execute(status -> sell(command, key, fingerprint, lines));
  }

  /**
   * The sale's one transaction: database work only. A refusal rolls it back whole, the sale and its
   * key with it, so a key whose sale was refused stays free for the corrected cart.
   */
  private SaleView sell(
      CheckoutCommand command, String key, String fingerprint, List<CheckoutLine> lines) {
    if (key != null) {
      // Same-key requests take turns, so a retry sent while the first is still running gets its
      // sale back rather than a conflict (or 422 idempotency_key_reused for another cart).
      var existing = sales.claimIdempotencyKey(key, fingerprint);
      if (existing.isPresent()) {
        return existing.get();
      }
    }
    // The pharmacy's settings, read once for the whole sale (PERF-10).
    boolean taxIncluded = settings.current().pricesIncludeTax();
    requireMultiCurrencyFor(command.payments());
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
    UUID payerUid = payerOf(command.memberUid(), command.customerUid());
    Basket basket =
        priceBasket(
            command.pricePlan(), command.customerUid(), customerType, payerUid, lines, products);
    Map<UUID, BigDecimal> prices = basket.prices();
    Map<UUID, PricingService.PricedLine> pricedBy = basket.lines();
    Map<UUID, PackagingService.PackLinkView> packs = lockProducts(products.keySet());

    // Last of the locks, after every call out: the branch's number row (gapless, held to commit).
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
                "product_inactive", product.name() + " is no longer sold.", field + ".productUid")
            .with("product", product.name());
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
                field + ".serialNumbers")
            .with("product", product.name());
      }
      if (serials.isEmpty()) {
        openPacksFor(packs.get(product.uid()), product.uid(), line.quantity(), number);
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
      PricingService.PricedLine priced = pricedBy.get(product.uid());
      boolean promoted = priced != null && "PROMOTION".equals(priced.source());
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
                product.warrantyMonths(),
                taxIncluded,
                // The class at the moment of sale (FRD-6) and the promotion that priced it (FRD-8).
                product.schedule() == null ? Schedule.OTC.name() : product.schedule().name(),
                promoted ? priced.promotionUid() : null,
                promoted ? priced.label() : null));
      }
    }
    requireControlledPrescription(command, products);
    return settle(
        command,
        number,
        key,
        fingerprint,
        customerName,
        saleLines,
        products,
        basket.plan(),
        basket.normalTotal());
  }

  /**
   * Takes the stock lock of every product the cart may draw on (each line's, and the pack it can be
   * opened from) up front, in one fixed order, before any stock is taken: two tills selling [A, B]
   * and [B, A] then queue rather than deadlock (the loser of a deadlock would be refused). Returns
   * the products' packs, read in one query, for break bulk below.
   */
  private Map<UUID, PackagingService.PackLinkView> lockProducts(java.util.Set<UUID> productUids) {
    Map<UUID, PackagingService.PackLinkView> packs =
        productUids.isEmpty() ? Map.of() : packaging.packsOf(List.copyOf(productUids));
    java.util.Set<UUID> all = new java.util.HashSet<>(productUids);
    packs.values().forEach(link -> all.add(link.packProductUid()));
    stock.lockProducts(all);
    return packs;
  }

  /**
   * The insurer of an insured sale: the member must be one of the customer's own memberships (422
   * member_not_of_customer), so a sale cannot be charged to someone else's insurance.
   */
  private UUID payerOf(UUID memberUid, UUID customerUid) {
    if (memberUid == null || customerUid == null) {
      return null;
    }
    return insurance.membersOf(customerUid).stream()
        .filter(m -> m.uid().equals(memberUid))
        .map(tz.co.pharmacy.modules.insurance.ports.inbound.MemberView::payerUid)
        .findFirst()
        .orElseThrow(
            () ->
                Refusal.invalid(
                    "member_not_of_customer",
                    "This insurance membership is not the customer's.",
                    "memberUid"));
  }

  /**
   * A cart priced: the plan it is sold under, the unit price per product, the Normal Sale total.
   */
  private record Basket(
      PricingService.ResolvedPlan plan,
      Map<UUID, BigDecimal> prices,
      BigDecimal normalTotal,
      Map<UUID, PricingService.PricedLine> lines) {}

  /**
   * The pricing plan: the customer's own (special prices, else their type's, else Normal Sale), or
   * another one chosen by someone allowed to. A plan's price replaces the Normal Sale price; Normal
   * Sale takes the branch price and the best running promotion, and quantity breaks apply. An
   * insured sale takes the insurer's plan, when it has one. Checkout and the coverage quote both
   * price here, so the quote is what the sale will be.
   */
  private Basket priceBasket(
      String pricePlan,
      UUID customerUid,
      String customerType,
      UUID payerUid,
      List<CheckoutLine> lines,
      Map<UUID, ProductView> products) {
    PricingService.ResolvedPlan plan =
        pricing.resolvePlan(pricePlan, customerUid, customerType, payerUid, calendar.today());
    Map<UUID, Integer> quantities = new java.util.LinkedHashMap<>();
    lines.forEach(l -> quantities.merge(l.productUid(), l.quantity(), Integer::sum));
    List<PricingService.PricedLine> priced =
        pricing.price(
            ExecutionContext.requireBranch(),
            customerType,
            customerUid,
            plan.choice(),
            quantities.entrySet().stream()
                .filter(e -> products.containsKey(e.getKey()))
                .map(e -> new PricingService.PriceRequest(e.getKey(), e.getValue()))
                .toList(),
            calendar.today());
    for (PricingService.PricedLine line : priced) {
      if (line.unpriced()) {
        ProductView product = products.get(line.productUid());
        throw Refusal.invalid(
            "no_plan_price",
            "No "
                + plan.plan().name()
                + " price for "
                + (product == null ? "this medicine" : product.name())
                + ".",
            "lines");
      }
    }
    BigDecimal normalTotal =
        priced.stream()
            .map(l -> l.retailPrice().multiply(BigDecimal.valueOf(l.quantity())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    Map<UUID, BigDecimal> prices =
        priced.stream()
            .collect(
                Collectors.toMap(
                    PricingService.PricedLine::productUid, PricingService.PricedLine::unitPrice));
    Map<UUID, PricingService.PricedLine> byProduct =
        priced.stream()
            .collect(
                Collectors.toMap(
                    PricingService.PricedLine::productUid, Function.identity(), (a, b) -> a));
    return new Basket(plan, prices, normalTotal, byProduct);
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

  /** A tender in another currency needs the plan's multi-currency module (403 otherwise). */
  private void requireMultiCurrencyFor(List<PaymentCommand> payments) {
    if (payments == null || ExecutionContext.current().map(f -> f.rootAdmin()).orElse(false)) {
      return;
    }
    String base = null;
    for (int i = 0; i < payments.size(); i++) {
      PaymentCommand payment = payments.get(i);
      if (payment == null || payment.currency() == null || payment.currency().isBlank()) {
        continue;
      }
      if (base == null) {
        base = settings.current().currency();
      }
      if (!payment.currency().trim().equalsIgnoreCase(base)) {
        try {
          entitlements.requireFeature(ExecutionContext.requireTenant(), PlanFeature.MULTI_CURRENCY);
        } catch (Refusal refusal) {
          throw refusal.about("payments[" + i + "].currency");
        }
      }
    }
  }

  /**
   * Break bulk at the till: when too few loose units are on hand and the product comes in a pack (a
   * strip from a box), opens just enough packs first. If even the packs fall short the sale reports
   * the unit's shortage as usual.
   */
  private void openPacksFor(
      PackagingService.PackLinkView link, UUID productUid, int quantity, String saleNumber) {
    if (link == null) {
      return;
    }
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
      String fingerprint,
      String customerName,
      List<SaleLineCommand> saleLines,
      Map<UUID, ProductView> products,
      PricingService.ResolvedPlan resolved,
      BigDecimal normalTotal) {
    PricingService.PlanView pricedUnder = resolved.plan();
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
              trimmed.channel(),
              // Verified for what the provider confirmed; only the sale's part is recorded on it.
              Amounts.money(trimmed.amount())));
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
                command.prescription(),
                pricedUnder.uid(),
                pricedUnder.name(),
                normalTotal,
                planSource(resolved),
                fingerprint));

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

  /**
   * FRD-14: where the sale's plan came from, kept on the sale for the reports. The customer's own
   * plan (their special prices, their type's, their insurer's) is their price and any seller may
   * use it: CUSTOMER. Another plan needs sales.price_plan: CHOSEN. Normal Sale by default: null.
   */
  private static String planSource(PricingService.ResolvedPlan resolved) {
    UUID plan = resolved.plan().uid();
    UUID own = resolved.customerDefault() == null ? null : resolved.customerDefault().uid();
    if (!java.util.Objects.equals(plan, own)) {
      return "CHOSEN";
    }
    return plan == null ? null : "CUSTOMER";
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
      switch (line.disposition()) {
        case SHELF ->
            stock.restore(
                line.batchUid(), line.quantity(), saleReturn.number(), line.serialNumbers());
        case DAMAGED ->
            writeOff(
                line.productUid(),
                line.batchUid(),
                line.quantity(),
                line.serialNumbers(),
                saleReturn.number(),
                "Returned damaged on " + saleReturn.number());
        case QUARANTINE -> {
          // Held aside, out of stock and not sellable, until a manager releases it.
        }
      }
    }
    sales.find(saleReturn.saleUid()).ifPresent(sale -> settleReturn(sale, saleReturn));
    return saleReturn;
  }

  /**
   * FRD-4: returned units that are not fit to sell are written off at cost: they go back on the
   * batch they were sold from (so the return's cost comes back to inventory, as for a restock) and
   * leave it again as a DAMAGE adjustment, which the books post as a stock loss.
   */
  private void writeOff(
      UUID productUid,
      UUID batchUid,
      int quantity,
      List<String> serialNumbers,
      String reference,
      String note) {
    stock.restore(batchUid, quantity, reference, serialNumbers);
    stock.adjust(
        new AdjustStockCommand(
            productUid,
            batchUid,
            -quantity,
            AdjustmentReason.DAMAGE,
            note,
            serialNumbers == null || serialNumbers.isEmpty() ? null : serialNumbers));
  }

  @Override
  @Transactional
  public SaleReturnView releaseReturnLine(UUID returnUid, UUID lineUid, ReturnDisposition outcome) {
    SaleService.ReleasedLine released = sales.releaseReturnLine(returnUid, lineUid, outcome);
    if (released.outcome() == ReturnDisposition.SHELF) {
      stock.restore(
          released.batchUid(), released.quantity(), released.reference(), released.serialNumbers());
    } else {
      writeOff(
          released.productUid(),
          released.batchUid(),
          released.quantity(),
          released.serialNumbers(),
          released.reference(),
          "Released from quarantine as damaged (" + released.reference() + ")");
    }
    return released.saleReturn();
  }

  /**
   * Scenario 8. The refund goes back the way the sale was paid (the sales module splits it): the
   * insurer's share comes off the claim, never to the patient; the share paid on credit or from
   * account credit goes back to the customer's account as a credit note, settling the sale's open
   * charge first; a voucher's share went back onto the voucher; the rest is paid out by the method
   * it came in by. The ledger posts each share to its own account, so the reductions here post
   * nothing more (their method names the account the return already credited).
   */
  private void settleReturn(SaleView sale, SaleReturnView saleReturn) {
    BigDecimal insurer = refundedBy(saleReturn, PaymentMethod.INSURANCE);
    if (insurer.signum() > 0) {
      insurance.reduceForReturn(
          sale.uid(), insurer, saleReturn.number(), PaymentMethod.INSURANCE.name());
    }
    BigDecimal toAccount =
        Amounts.money(
            refundedBy(saleReturn, PaymentMethod.CREDIT)
                .add(refundedBy(saleReturn, PaymentMethod.ACCOUNT_CREDIT)));
    if (toAccount.signum() > 0 && sale.customerUid() != null) {
      credit.creditNote(
          sale.customerUid(),
          sale.uid(),
          saleReturn.number(),
          toAccount,
          PaymentMethod.CREDIT.name());
    }
  }

  private static BigDecimal refundedBy(SaleReturnView saleReturn, PaymentMethod method) {
    return saleReturn.refunds() == null
        ? Amounts.ZERO
        : saleReturn.refunds().stream()
            .filter(r -> r.method() == method)
            .map(r -> r.amount())
            .reduce(Amounts.ZERO, BigDecimal::add);
  }

  @Override
  @Transactional(readOnly = true)
  public CoverageQuote coverageQuote(
      UUID memberUid,
      List<CheckoutLine> lines,
      String preauthNumber,
      UUID customerUid,
      String pricePlan,
      BigDecimal discountPercent) {
    List<CheckoutLine> basket = Checks.notEmpty(lines, "lines");
    for (int i = 0; i < basket.size(); i++) {
      Checks.required(basket.get(i).productUid(), "lines[" + i + "].productUid");
      Checks.positive(basket.get(i).quantity(), "lines[" + i + "].quantity");
    }
    Map<UUID, ProductView> products =
        catalogue
            .products(basket.stream().map(CheckoutLine::productUid).distinct().toList())
            .stream()
            .collect(Collectors.toMap(ProductView::uid, Function.identity()));
    String customerType = null;
    if (customerUid != null) {
      var customer =
          customers
              .find(customerUid)
              .orElseThrow(
                  () -> Refusal.invalid("customer_not_found", "No such customer.", "customerUid"));
      customerType = customer.type() == null ? null : customer.type().name();
    }
    Basket pricedBasket =
        priceBasket(
            pricePlan,
            customerUid,
            customerType,
            payerOf(memberUid, customerUid),
            basket,
            products);
    boolean taxIncluded = pricesIncludeTax();
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
              basket.get(i).quantity(),
              pricedBasket.prices().getOrDefault(product.uid(), product.sellingPrice()),
              null,
              product.taxRatePercent() == null ? BigDecimal.ZERO : product.taxRatePercent(),
              null,
              null,
              taxIncluded));
    }
    SalePreview preview = sales.preview(priced, discountPercent, List.of());
    return insurance.quote(memberUid, coverage(priced, preview, products), preauthNumber);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<SellableProduct> sellable(String q, UUID categoryUid, PageQuery query) {
    Page<ProductView> page =
        catalogue.products(new ProductFilter(q, categoryUid, true, null), query);
    return page.map(sellableAt(page.items()));
  }

  @Override
  @Transactional(readOnly = true)
  public List<SellableProduct> sellable(java.util.Collection<UUID> productUids) {
    if (productUids == null || productUids.isEmpty()) {
      return List.of();
    }
    if (productUids.size() > MAX_SELLABLE_UIDS) {
      throw Refusal.invalid(
              "too_many_uids",
              "Ask for at most " + MAX_SELLABLE_UIDS + " products at a time.",
              "uids")
          .with("max", MAX_SELLABLE_UIDS);
    }
    List<UUID> order = productUids.stream().distinct().toList();
    Map<UUID, ProductView> found =
        catalogue.products(order).stream()
            .filter(ProductView::active)
            .collect(Collectors.toMap(ProductView::uid, Function.identity(), (a, b) -> a));
    List<ProductView> products =
        order.stream().map(found::get).filter(java.util.Objects::nonNull).toList();
    return products.stream().map(sellableAt(products)).toList();
  }

  /** The most products {@link #sellable(java.util.Collection)} reads at once. */
  static final int MAX_SELLABLE_UIDS = 100;

  /** The till's view of each product: its stock here, what packs can be opened, what is held. */
  private Function<ProductView, SellableProduct> sellableAt(List<ProductView> products) {
    List<UUID> uids = products.stream().map(ProductView::uid).toList();
    Map<UUID, Integer> onHand = stockQueries.onHand(uids);
    Map<UUID, Integer> recalled = stockQueries.quarantined(uids);
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
    return product ->
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
            openable(packs.get(product.uid()), packsOnHand),
            recalled.getOrDefault(product.uid(), 0));
  }

  private static int openable(PackagingService.PackLinkView pack, Map<UUID, Integer> packsOnHand) {
    if (pack == null) {
      return 0;
    }
    return packsOnHand.getOrDefault(pack.packProductUid(), 0) * pack.unitsPerPack();
  }

  /** Whether the pharmacy's selling prices include VAT (its setting). */
  private boolean pricesIncludeTax() {
    return settings.current().pricesIncludeTax();
  }
}
