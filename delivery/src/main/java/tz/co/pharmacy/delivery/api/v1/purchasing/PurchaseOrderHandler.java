package tz.co.pharmacy.delivery.api.v1.purchasing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.delivery.api.Pages;
import tz.co.pharmacy.modules.purchasing.ports.inbound.CreatePurchaseOrderCommand;
import tz.co.pharmacy.modules.purchasing.ports.inbound.OrderLineCommand;
import tz.co.pharmacy.modules.purchasing.ports.inbound.PurchaseOrderService;
import tz.co.pharmacy.modules.purchasing.ports.inbound.PurchaseOrderStatus;
import tz.co.pharmacy.modules.purchasing.ports.inbound.PurchaseOrderView;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.Refusal;

/** Purchase orders of the request's branch. */
@RestController
@RequestMapping("/api/v1/purchase-orders")
@RequiredArgsConstructor
public class PurchaseOrderHandler {

  private final PurchaseOrderService purchaseOrders;

  @GetMapping
  @Access(scope = AccessScope.BRANCH, permission = "purchasing.view")
  public ApiResponse<Page<PurchaseOrderView>> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID supplierUid,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    PurchaseOrderStatus orderStatus =
        status == null || status.isBlank()
            ? null
            : Checks.oneOf(PurchaseOrderStatus.class, status, "status");
    return ApiResponse.ok(
        purchaseOrders.list(orderStatus, supplierUid, Pages.of(cursor, limit)),
        "Purchase orders retrieved");
  }

  @GetMapping("/{purchaseOrderUid}")
  @Access(scope = AccessScope.BRANCH, permission = "purchasing.view")
  public ApiResponse<PurchaseOrderView> get(@PathVariable UUID purchaseOrderUid) {
    return ApiResponse.ok(
        purchaseOrders
            .find(purchaseOrderUid)
            .orElseThrow(
                () -> Refusal.notFound("purchase_order_not_found", "No such purchase order.")),
        "Purchase order retrieved");
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.BRANCH, permission = "purchasing.manage")
  public ApiResponse<PurchaseOrderView> create(@RequestBody CreatePurchaseOrderRequest body) {
    List<OrderLineCommand> lines =
        body.lines() == null
            ? List.of()
            : body.lines().stream()
                .map(
                    line ->
                        new OrderLineCommand(
                            line.productUid(),
                            line.quantity() == null ? 0 : line.quantity(),
                            line.unitCost()))
                .toList();
    return ApiResponse.created(
        purchaseOrders.create(
            new CreatePurchaseOrderCommand(
                body.supplierUid(),
                body.expectedDate(),
                body.note(),
                lines,
                body.currency(),
                body.exchangeRate())),
        "Purchase order created");
  }

  @PostMapping("/{purchaseOrderUid}/cancel")
  @Access(scope = AccessScope.BRANCH, permission = "purchasing.manage")
  public ApiResponse<PurchaseOrderView> cancel(
      @PathVariable UUID purchaseOrderUid, @RequestBody(required = false) CancelRequest body) {
    return ApiResponse.ok(
        purchaseOrders.cancel(purchaseOrderUid, body == null ? null : body.reason()),
        "Purchase order cancelled");
  }

  public record CreatePurchaseOrderRequest(
      UUID supplierUid,
      LocalDate expectedDate,
      String note,
      List<OrderLineRequest> lines,
      /* ISO 4217; null means the base currency. */
      String currency,
      /* Base units per 1 order unit; null takes today's rate. */
      BigDecimal exchangeRate) {}

  public record OrderLineRequest(UUID productUid, Integer quantity, BigDecimal unitCost) {}

  public record CancelRequest(String reason) {}
}
