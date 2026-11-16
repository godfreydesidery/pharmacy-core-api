package tz.co.pharmacy.delivery.api.v1.catalogue;

import java.math.BigDecimal;
import java.time.LocalDate;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
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

/** Price lists, branch prices, promotions, and the price of a basket for a customer at the till. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class PricingHandler {

  private final PricingService pricing;
  private final CustomerService customers;

  @GetMapping("/price-lists")
  @Access(scope = AccessScope.TENANT, permission = "catalogue.view")
  public ApiResponse<List<PriceListView>> priceLists() {
    return ApiResponse.ok(pricing.priceLists(), "Price lists retrieved");
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

  /** {@code price} null removes the branch price. */
  public record BranchPriceRequest(UUID productUid, UUID branchUid, BigDecimal price) {}

  @PutMapping("/branch-prices")
  @Access(scope = AccessScope.TENANT, permission = "catalogue.manage")
  public ApiResponse<Void> setBranchPrice(@RequestBody BranchPriceRequest body) {
    if (body == null) {
      throw Refusal.malformed("The request needs a body.");
    }
    pricing.setBranchPrice(body.productUid(), body.branchUid(), body.price());
    return ApiResponse.ok(null, "Branch price saved");
  }

  @GetMapping("/promotions")
  @Access(scope = AccessScope.TENANT, permission = "catalogue.view")
  public ApiResponse<List<PromotionView>> promotions() {
    return ApiResponse.ok(pricing.promotions(), "Promotions retrieved");
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

  @DeleteMapping("/promotions/{uid}")
  @Access(scope = AccessScope.TENANT, permission = "catalogue.manage")
  public ApiResponse<Void> deletePromotion(@PathVariable UUID uid) {
    pricing.deletePromotion(uid);
    return ApiResponse.ok(null, "Promotion deleted");
  }

  public record QuoteLine(UUID productUid, Integer quantity) {}

  public record QuoteRequest(UUID customerUid, List<QuoteLine> lines) {}

  /** The till's prices for a basket: what checkout will charge, line by line. */
  @PostMapping("/pos/prices")
  @Access(scope = AccessScope.BRANCH, permission = "sales.sell")
  public ApiResponse<List<PricedLine>> quote(@RequestBody QuoteRequest body) {
    if (body == null) {
      throw Refusal.malformed("The request needs a body.");
    }
    List<QuoteLine> lines = Checks.notEmpty(body.lines(), "lines");
    String type = null;
    if (body.customerUid() != null) {
      var customer =
          customers
              .find(body.customerUid())
              .orElseThrow(
                  () -> Refusal.invalid("customer_not_found", "No such customer.", "customerUid"));
      type = customer.type() == null ? null : customer.type().name();
    }
    return ApiResponse.ok(
        pricing.price(
            ExecutionContext.requireBranch(),
            type,
            body.customerUid(),
            lines.stream()
                .map(
                    l ->
                        new PriceRequest(
                            Checks.required(l.productUid(), "lines.productUid"),
                            l.quantity() == null ? 1 : l.quantity()))
                .toList(),
            LocalDate.now()),
        "Prices retrieved");
  }

  private static PriceListCommand body(PriceListCommand body) {
    if (body == null) {
      throw Refusal.malformed("The request needs a body.");
    }
    return body;
  }
}
