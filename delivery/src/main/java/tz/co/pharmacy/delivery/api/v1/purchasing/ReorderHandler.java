package tz.co.pharmacy.delivery.api.v1.purchasing;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.modules.purchasing.ports.inbound.PurchaseOrderView;
import tz.co.pharmacy.modules.purchasing.ports.inbound.ReorderSuggestions;
import tz.co.pharmacy.modules.purchasing.ports.inbound.ReorderSuggestions.Plan;
import tz.co.pharmacy.modules.purchasing.ports.inbound.ReorderSuggestions.SupplierOrder;

/** What the request's branch should reorder, and turning the reviewed quantities into orders. */
@RestController
@RequestMapping("/api/v1/reorder-suggestions")
@RequiredArgsConstructor
public class ReorderHandler {

  private final ReorderSuggestions suggestions;

  @GetMapping
  @Access(scope = AccessScope.BRANCH, permission = "purchasing.view")
  public ApiResponse<Plan> suggest(
      @RequestParam(required = false) Integer windowDays,
      @RequestParam(required = false) Integer coverDays) {
    return ApiResponse.ok(
        suggestions.suggest(windowDays, coverDays), "Reorder suggestions retrieved");
  }

  @PostMapping("/orders")
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.BRANCH, permission = "purchasing.manage")
  public ApiResponse<List<PurchaseOrderView>> order(@RequestBody OrdersRequest body) {
    return ApiResponse.created(
        suggestions.order(body == null ? null : body.orders()), "Purchase orders created");
  }

  public record OrdersRequest(List<SupplierOrder> orders) {}
}
