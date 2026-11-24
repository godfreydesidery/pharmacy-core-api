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
import tz.co.pharmacy.delivery.api.Idempotent;
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
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    return ApiResponse.ok(
        supplierReturns.list(supplierUid, status, Pages.of(cursor, limit)),
        "Supplier returns retrieved");
  }

  @GetMapping("/{uid}")
  @Access(scope = AccessScope.BRANCH, permission = "purchasing.view")
  public ApiResponse<SupplierReturnView> get(@PathVariable UUID uid) {
    return ApiResponse.ok(supplierReturns.get(uid), "Supplier return retrieved");
  }

  @Idempotent
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.BRANCH, permission = "purchasing.receive")
  public ApiResponse<SupplierReturnView> record(@RequestBody ReturnToSupplierCommand body) {
    return ApiResponse.created(receiving.returnToSupplier(body), "Supplier return recorded");
  }

  /**
   * The supplier's credit note for a pending return (FRD-16): from then on the return lowers what
   * is owed to the supplier and what is open on its goods receipt. Recorded by whoever keeps the
   * supplier accounts, at any branch they may use.
   */
  @Idempotent
  @PostMapping("/{uid}/credit-note")
  @Access(scope = AccessScope.TENANT_OPTIONAL_BRANCH, permission = "finance.manage")
  public ApiResponse<SupplierReturnView> credit(
      @PathVariable UUID uid, @RequestBody SupplierReturns.CreditNoteCommand body) {
    return ApiResponse.ok(supplierReturns.credit(uid, body), "Credit note recorded");
  }
}
