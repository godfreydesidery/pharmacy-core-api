package tz.co.pharmacy.delivery.api.v1.purchasing;

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
import tz.co.pharmacy.application.receiving.ports.inbound.Receiving;
import tz.co.pharmacy.application.receiving.ports.inbound.Receiving.ReturnToSupplierCommand;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.delivery.api.Pages;
import tz.co.pharmacy.modules.purchasing.ports.inbound.SupplierReturns;
import tz.co.pharmacy.modules.purchasing.ports.inbound.SupplierReturns.SupplierReturnView;
import tz.co.pharmacy.platform.kernel.Page;

/**
 * Returns to suppliers (debit notes) of the request's branch; recording one takes the stock out.
 */
@RestController
@RequestMapping("/api/v1/supplier-returns")
@RequiredArgsConstructor
public class SupplierReturnHandler {

  private final SupplierReturns supplierReturns;
  private final Receiving receiving;

  @GetMapping
  @Access(scope = AccessScope.BRANCH, permission = "purchasing.view")
  public ApiResponse<Page<SupplierReturnView>> list(
      @RequestParam(required = false) UUID supplierUid,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    return ApiResponse.ok(
        supplierReturns.list(supplierUid, Pages.of(cursor, limit)), "Supplier returns retrieved");
  }

  @GetMapping("/{uid}")
  @Access(scope = AccessScope.BRANCH, permission = "purchasing.view")
  public ApiResponse<SupplierReturnView> get(@PathVariable UUID uid) {
    return ApiResponse.ok(supplierReturns.get(uid), "Supplier return retrieved");
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.BRANCH, permission = "purchasing.receive")
  public ApiResponse<SupplierReturnView> record(@RequestBody ReturnToSupplierCommand body) {
    return ApiResponse.created(receiving.returnToSupplier(body), "Supplier return recorded");
  }
}
