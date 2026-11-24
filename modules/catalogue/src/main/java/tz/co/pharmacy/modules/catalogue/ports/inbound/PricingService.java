package tz.co.pharmacy.modules.catalogue.ports.inbound;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Prices beyond the catalogue price: price lists (per customer type or for one customer, with
 * quantity breaks), branch prices and promotions; and the price of a line for a customer at a
 * branch.
 */
public interface PricingService {

  /**
   * Every plan, by name, without its items ({@code items} null; {@code itemCount} says how many): a
   * plan's items are read a page at a time ({@link #planItems}) or with {@link #priceList}.
   */
  List<PriceListView> priceLists();

  /** One plan with all its items. */
  java.util.Optional<PriceListView> priceList(UUID uid);

  /**
   * Creates ({@code uid} null) or replaces a price list and its items. A change that names no items
   * (null or empty) keeps the plan's items.
   */
  PriceListView savePriceList(UUID uid, PriceListCommand command);

  void deletePriceList(UUID uid);

  List<BranchPriceView> branchPrices();

  /** Sets a product's price at a branch; a null price removes it. */
  void setBranchPrice(UUID productUid, UUID branchUid, BigDecimal price);

  List<PromotionView> promotions();

  /**
   * Creates ({@code uid} null) or changes a promotion. Saving one that is on and deeper than the
   * pharmacy's maximum discount needs a manager's approval each time (403 approval_required,
   * DEEP_PROMOTION, amount the percentOff); switching one off never does.
   */
  PromotionView savePromotion(UUID uid, PromotionCommand command);

  /**
   * Switches the promotion off; it is never deleted, so sales priced by it keep naming it (FRD-8).
   * 404 promotion_not_found.
   */
  PromotionView switchOffPromotion(UUID uid);

  /**
   * Each line's unit price for the customer (type and uid, either may be null) at the branch on the
   * day, with where it came from; the catalogue price when nothing else applies.
   */
  List<PricedLine> price(
      UUID branchUid,
      String customerType,
      UUID customerUid,
      List<PriceRequest> lines,
      LocalDate on);

  /**
   * The prices of a basket under a chosen plan: {@code null} takes the customer's own plan (their
   * special prices, else their type's), {@link PlanChoice#NORMAL} the Normal Sale prices, any other
   * choice that plan. A plan's price replaces the Normal Sale price (no promotion on top); a
   * medicine the plan has no price for takes the plan's rule, or is marked {@code unpriced} when it
   * has none. 422 price_plan_not_found for a plan that is missing, switched off or out of its
   * dates.
   */
  List<PricedLine> price(
      UUID branchUid,
      String customerType,
      UUID customerUid,
      PlanChoice plan,
      List<PriceRequest> lines,
      LocalDate on);

  /** The plan a customer buys under by default: their own, else their type's, else Normal Sale. */
  PlanView defaultPlan(UUID customerUid, String customerType, LocalDate on);

  /** The permission to sell under another plan than the customer's own. */
  String CHANGE_PLAN_PERMISSION = "sales.price_plan";

  /**
   * The plan a sale is priced under: the customer's own unless {@code requested} names another
   * ({@code NORMAL} or a plan's uid), which needs {@link #CHANGE_PLAN_PERMISSION} (403
   * price_plan_not_allowed) and an active plan in its dates (422 price_plan_not_found).
   */
  ResolvedPlan resolvePlan(String requested, UUID customerUid, String customerType, LocalDate on);

  /**
   * As {@link #resolvePlan(String, UUID, String, LocalDate)} for an insured sale: the insurer's
   * plan ({@code payerUid}), when it has one, comes before the customer's.
   */
  ResolvedPlan resolvePlan(
      String requested, UUID customerUid, String customerType, UUID payerUid, LocalDate on);

  /** The default plan of an insured sale: the insurer's, else the customer's. */
  PlanView defaultPlan(UUID customerUid, String customerType, UUID payerUid, LocalDate on);

  /**
   * Sets many prices at once: Normal Sale ({@code planUid} null) or a plan, to the Normal Sale
   * price ± {@code percent}, rounded to {@code roundTo} (NEAREST, UP or DOWN), for one category or
   * all, and for a plan only its medicines without a price when {@code onlyMissing}. {@code apply}
   * false previews; true saves, writing the price history (BULK).
   */
  BulkResult bulk(BulkCommand command, boolean apply);

  record BulkCommand(
      UUID planUid,
      BigDecimal percent,
      BigDecimal roundTo,
      String rounding,
      UUID categoryUid,
      Boolean onlyMissing) {}

  record BulkResult(String planName, List<BulkLine> lines, boolean applied) {}

  record BulkLine(
      UUID productUid, String code, String name, BigDecimal oldPrice, BigDecimal newPrice) {}

  /** The plan to price with ({@code choice}, null for the customer's own) and the plan itself. */
  record ResolvedPlan(PlanChoice choice, PlanView plan, PlanView customerDefault) {}

  /** Normal Sale first, then every active plan, by name. */
  List<PlanView> plans();

  /**
   * The pricing grid: every active medicine with its Normal Sale price and its price in each plan,
   * filtered by text (name, code, barcode) and category; {@code missingOnly} keeps the medicines
   * that some plan without a rule has no price for.
   */
  tz.co.pharmacy.platform.kernel.Page<GridRow> grid(
      String q,
      UUID categoryUid,
      boolean missingOnly,
      tz.co.pharmacy.platform.kernel.PageQuery query);

  /**
   * Sets one price: the Normal Sale price ({@code planUid} null) or the medicine's price in a plan
   * (from quantity 1); {@code price} null removes it from the plan. Writes the price history.
   */
  GridRow setPrice(UUID productUid, UUID planUid, BigDecimal price);

  /** As {@link #setPrice(UUID, UUID, BigDecimal)} from {@code minQuantity} (a quantity break). */
  GridRow setPrice(UUID productUid, UUID planUid, int minQuantity, BigDecimal price);

  /** Normal Sale and every plan (active or not), each with how many medicines it prices. */
  List<PlanCard> planCards();

  /**
   * One plan's rows: Normal Sale ({@code planUid} null) lists every active medicine; a plan lists
   * the medicines it prices (a row per quantity break), or with {@code notPriced} the active
   * medicines it has no price for. By text (name, code, barcode) and category.
   */
  tz.co.pharmacy.platform.kernel.Page<PlanItemRow> planItems(
      UUID planUid,
      String q,
      UUID categoryUid,
      boolean notPriced,
      tz.co.pharmacy.platform.kernel.PageQuery query);

  /** A medicine's price in Normal Sale and every plan: set, by the plan's rule, or blocked. */
  List<PlanPriceView> planPrices(UUID productUid);

  record PlanCard(
      UUID uid,
      String name,
      String customerType,
      UUID customerUid,
      String customerName,
      UUID payerUid,
      String payerName,
      BigDecimal rulePercent,
      boolean active,
      LocalDate validFrom,
      LocalDate validTo,
      long priced,
      /* The plan's version (null for Normal Sale), for If-Match when the plan is changed. */
      Long version) {}

  /** {@code price} null: not priced in the plan. */
  record PlanItemRow(
      UUID productUid,
      String code,
      String name,
      String barcode,
      String category,
      BigDecimal costPrice,
      BigDecimal normalPrice,
      int minQuantity,
      BigDecimal price) {}

  /** {@code basis}: PRICE (set in the plan), RULE (Normal ± the plan's %) or BLOCKED. */
  record PlanPriceView(
      UUID planUid,
      String planName,
      BigDecimal price,
      String basis,
      List<PriceBreak> breaks,
      boolean active) {}

  record PriceBreak(int minQuantity, BigDecimal price) {}

  /** A medicine's price changes, newest first. */
  List<PriceChangeView> history(UUID productUid);

  /** As {@link #savePriceList}, for an import: its price history says so. */
  PriceListView importPriceList(UUID uid, PriceListCommand command);

  /** Normal Sale ({@code planUid} null) or one plan, chosen for a sale. */
  record PlanChoice(UUID planUid) {
    public static final PlanChoice NORMAL = new PlanChoice(null);
  }

  /** A plan; {@code uid} null is Normal Sale. {@code rulePercent} null: no rule (blocks). */
  record PlanView(
      UUID uid,
      String name,
      BigDecimal rulePercent,
      String customerType,
      UUID customerUid,
      String customerName,
      UUID payerUid,
      String payerName) {
    public static PlanView normal() {
      return new PlanView(null, "Normal Sale", BigDecimal.ZERO, null, null, null, null, null);
    }
  }

  /** One row of the grid; {@code prices} holds each plan's price from quantity 1, where set. */
  record GridRow(
      UUID productUid,
      String code,
      String name,
      String barcode,
      String category,
      BigDecimal costPrice,
      BigDecimal normalPrice,
      Map<UUID, BigDecimal> prices) {}

  record PriceChangeView(
      UUID planUid,
      String planName,
      int minQuantity,
      BigDecimal oldPrice,
      BigDecimal newPrice,
      String source,
      String changedBy,
      java.time.Instant changedAt) {}

  record PriceListCommand(
      String name,
      String customerType,
      UUID customerUid,
      String customerName,
      BigDecimal adjustmentPercent,
      LocalDate validFrom,
      LocalDate validTo,
      Boolean active,
      List<ItemCommand> items,
      UUID payerUid,
      String payerName) {
    /** A plan that is not an insurer's. */
    public PriceListCommand(
        String name,
        String customerType,
        UUID customerUid,
        String customerName,
        BigDecimal adjustmentPercent,
        LocalDate validFrom,
        LocalDate validTo,
        Boolean active,
        List<ItemCommand> items) {
      this(
          name,
          customerType,
          customerUid,
          customerName,
          adjustmentPercent,
          validFrom,
          validTo,
          active,
          items,
          null,
          null);
    }
  }

  record ItemCommand(UUID productUid, Integer minQuantity, BigDecimal price) {}

  record PriceListView(
      UUID uid,
      String name,
      String customerType,
      UUID customerUid,
      String customerName,
      BigDecimal adjustmentPercent,
      LocalDate validFrom,
      LocalDate validTo,
      boolean active,
      /* null in the list of plans (priceLists); every item for one plan. */
      List<ItemView> items,
      UUID payerUid,
      String payerName,
      int itemCount,
      /* The plan's version: the ETag of /price-lists/{uid} and what If-Match names. */
      long version)
      implements tz.co.pharmacy.platform.kernel.Versioned {}

  record ItemView(UUID productUid, String productName, int minQuantity, BigDecimal price) {}

  record BranchPriceView(UUID productUid, String productName, UUID branchUid, BigDecimal price) {}

  record PromotionCommand(
      String name,
      BigDecimal percentOff,
      UUID productUid,
      UUID categoryUid,
      UUID branchUid,
      LocalDate validFrom,
      LocalDate validTo,
      Boolean active) {}

  record PromotionView(
      UUID uid,
      String name,
      BigDecimal percentOff,
      UUID productUid,
      String productName,
      UUID categoryUid,
      String categoryName,
      UUID branchUid,
      LocalDate validFrom,
      LocalDate validTo,
      boolean active) {}

  record PriceRequest(UUID productUid, int quantity) {}

  /**
   * {@code source}: RETAIL, BRANCH, LIST or PROMOTION; {@code label} names the list or promotion,
   * and {@code promotionUid} the promotion (source PROMOTION). {@code belowCost} warns when the
   * price is under the product's cost.
   */
  record PricedLine(
      UUID productUid,
      int quantity,
      BigDecimal unitPrice,
      BigDecimal retailPrice,
      String source,
      String label,
      boolean belowCost,
      /* The chosen plan has no price and no rule for it: the sale is refused. */
      boolean unpriced,
      UUID promotionUid) {}
}
