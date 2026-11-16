package tz.co.pharmacy.modules.catalogue.ports.inbound;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Prices beyond the catalogue price: price lists (per customer type or for one customer, with
 * quantity breaks), branch prices and promotions; and the price of a line for a customer at a
 * branch.
 */
public interface PricingService {

  List<PriceListView> priceLists();

  /** Creates ({@code uid} null) or replaces a price list and its items. */
  PriceListView savePriceList(UUID uid, PriceListCommand command);

  void deletePriceList(UUID uid);

  List<BranchPriceView> branchPrices();

  /** Sets a product's price at a branch; a null price removes it. */
  void setBranchPrice(UUID productUid, UUID branchUid, BigDecimal price);

  List<PromotionView> promotions();

  PromotionView savePromotion(UUID uid, PromotionCommand command);

  void deletePromotion(UUID uid);

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

  record PriceListCommand(
      String name,
      String customerType,
      UUID customerUid,
      String customerName,
      BigDecimal adjustmentPercent,
      LocalDate validFrom,
      LocalDate validTo,
      Boolean active,
      List<ItemCommand> items) {}

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
      List<ItemView> items) {}

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
   * {@code source}: RETAIL, BRANCH, LIST or PROMOTION; {@code label} names the list or promotion.
   * {@code belowCost} warns when the price is under the product's cost.
   */
  record PricedLine(
      UUID productUid,
      int quantity,
      BigDecimal unitPrice,
      BigDecimal retailPrice,
      String source,
      String label,
      boolean belowCost) {}
}
