package tz.co.pharmacy.delivery.api.v1.catalogue;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.delivery.api.Pages;
import tz.co.pharmacy.delivery.edge.BranchAccess;
import tz.co.pharmacy.modules.catalogue.ports.inbound.PricingService;
import tz.co.pharmacy.modules.catalogue.ports.inbound.PricingService.BranchPriceView;
import tz.co.pharmacy.modules.catalogue.ports.inbound.PricingService.PriceListCommand;
import tz.co.pharmacy.modules.catalogue.ports.inbound.PricingService.PriceListView;
import tz.co.pharmacy.modules.catalogue.ports.inbound.PricingService.PriceRequest;
import tz.co.pharmacy.modules.catalogue.ports.inbound.PricingService.PricedLine;
import tz.co.pharmacy.modules.catalogue.ports.inbound.PricingService.PromotionCommand;
import tz.co.pharmacy.modules.catalogue.ports.inbound.PricingService.PromotionView;
import tz.co.pharmacy.modules.partners.ports.inbound.CustomerService;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.kernel.TenantCalendar;

/** Price lists, branch prices, promotions, and the price of a basket for a customer at the till. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class PricingHandler {

  private final PricingService pricing;
  private final CustomerService customers;
  private final TenantCalendar calendar;
  private final tz.co.pharmacy.modules.insurance.ports.inbound.Insurance insurance;
  private final tz.co.pharmacy.modules.sales.ports.inbound.SaleService sales;

  @GetMapping("/price-lists")
  @Access(scope = AccessScope.TENANT, permission = "catalogue.view")
  public ApiResponse<?> priceLists() {
    return ApiResponse.ok(Pages.slice(pricing.priceLists()), "Price lists retrieved");
  }

  /**
   * One plan with all its items. The list above comes without them (PERF-11); a plan's items are
   * also served a page at a time by {@code GET /pricing-plans/{plan}/items}.
   */
  @GetMapping("/price-lists/{uid}")
  @Access(scope = AccessScope.TENANT, permission = "catalogue.view")
  public ApiResponse<PriceListView> priceList(@PathVariable UUID uid) {
    return ApiResponse.ok(
        pricing
            .priceList(uid)
            .orElseThrow(() -> Refusal.notFound("price_list_not_found", "No such price list.")),
        "Price list retrieved");
  }

  @PostMapping("/price-lists")
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.TENANT, permission = "catalogue.manage")
  public ApiResponse<PriceListView> createPriceList(@RequestBody PriceListCommand body) {
    return ApiResponse.created(pricing.savePriceList(null, body(body)), "Price list created");
  }

  @PutMapping("/price-lists/{uid}")
  @Access(scope = AccessScope.TENANT, permission = "catalogue.manage")
  public ApiResponse<PriceListView> changePriceList(
      @PathVariable UUID uid, @RequestBody PriceListCommand body) {
    return ApiResponse.ok(pricing.savePriceList(uid, body(body)), "Price list saved");
  }

  @DeleteMapping("/price-lists/{uid}")
  @Access(scope = AccessScope.TENANT, permission = "catalogue.manage")
  public ApiResponse<Void> deletePriceList(@PathVariable UUID uid) {
    pricing.deletePriceList(uid);
    return ApiResponse.ok(null, "Price list deleted");
  }

  @GetMapping("/branch-prices")
  @Access(scope = AccessScope.TENANT, permission = "catalogue.view")
  public ApiResponse<List<BranchPriceView>> branchPrices() {
    return ApiResponse.ok(pricing.branchPrices(), "Branch prices retrieved");
  }

  /**
   * {@code price} null removes the branch price. The branch must be one the caller may work in (403
   * branch_not_allowed), as when it is named in {@code X-Branch-Id} (ISO-9).
   */
  public record BranchPriceRequest(UUID productUid, UUID branchUid, BigDecimal price) {}

  @PutMapping("/branch-prices")
  @Access(scope = AccessScope.TENANT, permission = "catalogue.manage")
  public ApiResponse<Void> setBranchPrice(@RequestBody BranchPriceRequest body) {
    if (body == null) {
      throw Refusal.malformed("The request needs a body.");
    }
    pricing.setBranchPrice(
        body.productUid(), BranchAccess.requireAllowedBranch(body.branchUid()), body.price());
    return ApiResponse.ok(null, "Branch price saved");
  }

  @GetMapping("/promotions")
  @Access(scope = AccessScope.TENANT, permission = "catalogue.view")
  public ApiResponse<?> promotions() {
    return ApiResponse.ok(Pages.slice(pricing.promotions()), "Promotions retrieved");
  }

  @PostMapping("/promotions")
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.TENANT, permission = "catalogue.manage")
  public ApiResponse<PromotionView> createPromotion(@RequestBody PromotionCommand body) {
    if (body == null) {
      throw Refusal.malformed("The request needs a body.");
    }
    return ApiResponse.created(pricing.savePromotion(null, body), "Promotion created");
  }

  @PutMapping("/promotions/{uid}")
  @Access(scope = AccessScope.TENANT, permission = "catalogue.manage")
  public ApiResponse<PromotionView> changePromotion(
      @PathVariable UUID uid, @RequestBody PromotionCommand body) {
    if (body == null) {
      throw Refusal.malformed("The request needs a body.");
    }
    return ApiResponse.ok(pricing.savePromotion(uid, body), "Promotion saved");
  }

  /**
   * Switches the promotion off. A promotion is never deleted (FRD-8): the sales it priced keep
   * naming it, and the list still shows it, inactive.
   */
  @DeleteMapping("/promotions/{uid}")
  @Access(scope = AccessScope.TENANT, permission = "catalogue.manage")
  public ApiResponse<PromotionView> deletePromotion(@PathVariable UUID uid) {
    return ApiResponse.ok(pricing.switchOffPromotion(uid), "Promotion switched off");
  }

  public record QuoteLine(UUID productUid, Integer quantity) {}

  public record QuoteRequest(
      UUID customerUid, List<QuoteLine> lines, String pricePlan, UUID memberUid) {}

  /** The till's prices for a basket: what checkout will charge, line by line. */
  @PostMapping("/pos/prices")
  @Access(scope = AccessScope.BRANCH, permission = "sales.sell")
  public ApiResponse<List<PricedLine>> quote(@RequestBody QuoteRequest body) {
    if (body == null) {
      throw Refusal.malformed("The request needs a body.");
    }
    List<QuoteLine> lines = Checks.notEmpty(body.lines(), "lines");
    String type = customerType(body.customerUid());
    PricingService.ResolvedPlan plan =
        pricing.resolvePlan(
            body.pricePlan(),
            body.customerUid(),
            type,
            payerOf(body.customerUid(), body.memberUid()),
            calendar.today());
    return ApiResponse.ok(
        pricing.price(
            ExecutionContext.requireBranch(),
            type,
            body.customerUid(),
            plan.choice(),
            lines.stream()
                .map(
                    l ->
                        new PriceRequest(
                            Checks.required(l.productUid(), "lines.productUid"),
                            l.quantity() == null ? 1 : l.quantity()))
                .toList(),
            calendar.today()),
        "Prices retrieved");
  }

  private String customerType(UUID customerUid) {
    if (customerUid == null) {
      return null;
    }
    var customer =
        customers
            .find(customerUid)
            .orElseThrow(
                () -> Refusal.invalid("customer_not_found", "No such customer.", "customerUid"));
    return customer.type() == null ? null : customer.type().name();
  }

  /**
   * The till's pricing plans for a customer: the one the sale takes by default, every plan it could
   * take, and whether this person may switch.
   */
  @GetMapping("/pos/price-plans")
  @Access(scope = AccessScope.BRANCH, permission = "sales.sell")
  public ApiResponse<TillPlans> tillPlans(
      @RequestParam(required = false) UUID customerUid,
      @RequestParam(required = false) UUID memberUid) {
    PricingService.PlanView own =
        pricing.defaultPlan(
            customerUid,
            customerType(customerUid),
            payerOf(customerUid, memberUid),
            calendar.today());
    return ApiResponse.ok(
        new TillPlans(
            own, pricing.plans(), ExecutionContext.permits(PricingService.CHANGE_PLAN_PERMISSION)),
        "Pricing plans retrieved");
  }

  public record TillPlans(
      PricingService.PlanView defaultPlan,
      List<PricingService.PlanView> plans,
      boolean canChange) {}

  /** Normal Sale and every active plan, for the pricing page's columns. */
  @GetMapping("/pricing-plans")
  @Access(scope = AccessScope.TENANT, permission = "catalogue.view")
  public ApiResponse<List<PricingService.PlanView>> plans() {
    return ApiResponse.ok(pricing.plans(), "Pricing plans retrieved");
  }

  /**
   * The pricing grid: each active medicine's Normal Sale price and its price in every plan, by
   * {@code q} (name, code, barcode), {@code categoryUid}, and {@code missing=true} for the
   * medicines some plan has no price (and no rule) for.
   */
  @GetMapping("/pricing-plans/grid")
  @Access(scope = AccessScope.TENANT, permission = "catalogue.view")
  public ApiResponse<tz.co.pharmacy.platform.kernel.Page<PricingService.GridRow>> grid(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) UUID categoryUid,
      @RequestParam(required = false) Boolean missing,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    return ApiResponse.ok(
        pricing.grid(q, categoryUid, Boolean.TRUE.equals(missing), Pages.of(cursor, limit)),
        "Pricing grid retrieved");
  }

  /**
   * Sets one price: {@code plan} is {@code normal} (the medicine's Normal Sale price) or a plan's
   * uid. A null price removes the medicine from the plan.
   */
  @PutMapping("/pricing-plans/{plan}/prices/{productUid}")
  @Access(scope = AccessScope.TENANT, permission = "catalogue.manage")
  public ApiResponse<PricingService.GridRow> setPrice(
      @PathVariable String plan,
      @PathVariable UUID productUid,
      @RequestParam(required = false) Integer minQuantity,
      @RequestBody PriceBody body) {
    return ApiResponse.ok(
        pricing.setPrice(
            productUid,
            planUid(plan),
            minQuantity == null ? 1 : minQuantity,
            body == null ? null : body.price()),
        "Price saved");
  }

  @DeleteMapping("/pricing-plans/{plan}/prices/{productUid}")
  @Access(scope = AccessScope.TENANT, permission = "catalogue.manage")
  public ApiResponse<PricingService.GridRow> removePrice(
      @PathVariable String plan,
      @PathVariable UUID productUid,
      @RequestParam(required = false) Integer minQuantity) {
    UUID uid = planUid(plan);
    if (uid == null) {
      throw Refusal.invalid("invalid_price", "Every medicine has a Normal Sale price.", "plan");
    }
    return ApiResponse.ok(
        pricing.setPrice(productUid, uid, minQuantity == null ? 1 : minQuantity, null),
        "Price removed");
  }

  /** Normal Sale and every plan, each with how many medicines it prices: the plan cards. */
  @GetMapping("/pricing-plans/cards")
  @Access(scope = AccessScope.TENANT, permission = "catalogue.view")
  public ApiResponse<List<PricingService.PlanCard>> cards() {
    return ApiResponse.ok(pricing.planCards(), "Pricing plans retrieved");
  }

  /**
   * One plan's medicines: {@code plan} is {@code normal} (every medicine) or a plan's uid (the
   * medicines it prices, a row per quantity break; {@code notPriced=true}: those it does not).
   */
  @GetMapping("/pricing-plans/{plan}/items")
  @Access(scope = AccessScope.TENANT, permission = "catalogue.view")
  public ApiResponse<tz.co.pharmacy.platform.kernel.Page<PricingService.PlanItemRow>> planItems(
      @PathVariable String plan,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) UUID categoryUid,
      @RequestParam(required = false) Boolean notPriced,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    return ApiResponse.ok(
        pricing.planItems(
            planUid(plan), q, categoryUid, Boolean.TRUE.equals(notPriced), Pages.of(cursor, limit)),
        "Plan prices retrieved");
  }

  /** A medicine's price in Normal Sale and every plan. */
  @GetMapping("/products/{productUid}/plan-prices")
  @Access(scope = AccessScope.TENANT, permission = "catalogue.view")
  public ApiResponse<List<PricingService.PlanPriceView>> planPrices(@PathVariable UUID productUid) {
    return ApiResponse.ok(pricing.planPrices(productUid), "Prices in plans retrieved");
  }

  /** A medicine's price changes, newest first: which plan, old and new price, who and when. */
  @GetMapping("/products/{productUid}/price-history")
  @Access(scope = AccessScope.TENANT, permission = "catalogue.view")
  public ApiResponse<List<PricingService.PriceChangeView>> history(@PathVariable UUID productUid) {
    return ApiResponse.ok(pricing.history(productUid), "Price history retrieved");
  }

  public record PriceBody(BigDecimal price) {}

  /** The insurer of an insured sale's member, or null. */
  private UUID payerOf(UUID customerUid, UUID memberUid) {
    if (customerUid == null || memberUid == null) {
      return null;
    }
    return insurance.membersOf(customerUid).stream()
        .filter(m -> m.uid().equals(memberUid))
        .map(tz.co.pharmacy.modules.insurance.ports.inbound.MemberView::payerUid)
        .findFirst()
        .orElse(null);
  }

  /**
   * Bulk update, previewed: {@code plan} is {@code normal} or a plan's uid; the body sets Normal ±
   * {@code percent}, rounded to {@code roundTo} (NEAREST, UP or DOWN), for {@code categoryUid} or
   * all, and for a plan only its medicines without a price when {@code onlyMissing}.
   */
  @PostMapping("/pricing-plans/{plan}/bulk/preview")
  @Access(scope = AccessScope.TENANT, permission = "catalogue.manage")
  public ApiResponse<PricingService.BulkResult> bulkPreview(
      @PathVariable String plan, @RequestBody BulkBody body) {
    return ApiResponse.ok(pricing.bulk(bulk(plan, body), false), "Bulk update previewed");
  }

  /** The bulk update saved, with its price history. */
  @PostMapping("/pricing-plans/{plan}/bulk")
  @Access(scope = AccessScope.TENANT, permission = "catalogue.manage")
  public ApiResponse<PricingService.BulkResult> bulkApply(
      @PathVariable String plan, @RequestBody BulkBody body) {
    return ApiResponse.ok(pricing.bulk(bulk(plan, body), true), "Prices updated");
  }

  public record BulkBody(
      BigDecimal percent,
      BigDecimal roundTo,
      String rounding,
      UUID categoryUid,
      Boolean onlyMissing) {}

  private static PricingService.BulkCommand bulk(String plan, BulkBody body) {
    if (body == null) {
      throw Refusal.malformed("The request needs a body.");
    }
    return new PricingService.BulkCommand(
        planUid(plan),
        body.percent(),
        body.roundTo(),
        body.rounding(),
        body.categoryUid(),
        body.onlyMissing());
  }

  /**
   * Sales per pricing plan from {@code from} to {@code to} (pharmacy days, inclusive): how many,
   * what they charged, their Normal Sale value and the difference.
   */
  @GetMapping("/reports/pricing-plans")
  @Access(scope = AccessScope.TENANT, permission = "reports.view")
  public ApiResponse<List<tz.co.pharmacy.modules.sales.ports.inbound.SaleService.PlanSalesView>>
      byPlan(@RequestParam java.time.LocalDate from, @RequestParam java.time.LocalDate to) {
    if (to.isBefore(from)) {
      throw Refusal.invalid("invalid_period", "to is before from.", "to");
    }
    java.time.ZoneId zone = calendar.zone();
    return ApiResponse.ok(
        sales.byPricingPlan(
            from.atStartOfDay(zone).toInstant(), to.plusDays(1).atStartOfDay(zone).toInstant()),
        "Sales by pricing plan retrieved");
  }

  private static UUID planUid(String plan) {
    return "normal".equalsIgnoreCase(plan)
        ? null
        : tz.co.pharmacy.platform.kernel.Uids.parse(plan, "plan");
  }

  private static PriceListCommand body(PriceListCommand body) {
    if (body == null) {
      throw Refusal.malformed("The request needs a body.");
    }
    return body;
  }
}
