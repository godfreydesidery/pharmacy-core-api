package tz.co.pharmacy.delivery.api.v1.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
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
import tz.co.pharmacy.delivery.api.Idempotent;
import tz.co.pharmacy.delivery.api.Pages;
import tz.co.pharmacy.modules.finance.ports.inbound.SupplierPaymentCommand;
import tz.co.pharmacy.modules.finance.ports.inbound.SupplierPaymentView;
import tz.co.pharmacy.modules.finance.ports.inbound.SupplierPayments;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.Refusal;

/** Payments to suppliers; a foreign-currency payment realises its exchange difference. */
@RestController
@RequestMapping("/api/v1/finance/supplier-payments")
public class SupplierPaymentHandler {

  private final SupplierPayments payments;

  public SupplierPaymentHandler(SupplierPayments payments) {
    this.payments = payments;
  }

  @GetMapping
  @Access(scope = AccessScope.TENANT_OPTIONAL_BRANCH, permission = "finance.view")
  public ApiResponse<Page<SupplierPaymentView>> list(
      @RequestParam(required = false) UUID supplierUid,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    return ApiResponse.ok(
        payments.list(supplierUid, Pages.of(cursor, limit)), "Supplier payments retrieved");
  }

  @Idempotent
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.TENANT_OPTIONAL_BRANCH, permission = "finance.manage")
  public ApiResponse<SupplierPaymentView> record(@RequestBody SupplierPaymentRequest body) {
    if (body == null) {
      throw Refusal.malformed("A payment is required.");
    }
    return ApiResponse.created(
        payments.record(
            new SupplierPaymentCommand(
                body.supplierUid(),
                body.amount(),
                body.currency(),
                body.rate(),
                body.paymentMethod(),
                body.reference(),
                body.goodsReceiptUids(),
                body.paidOn())),
        "Supplier payment recorded");
  }

  /** {@code rate} is base units per 1 {@code currency}; null takes the rate on {@code paidOn}. */
  public record SupplierPaymentRequest(
      UUID supplierUid,
      BigDecimal amount,
      String currency,
      BigDecimal rate,
      String paymentMethod,
      String reference,
      List<UUID> goodsReceiptUids,
      LocalDate paidOn) {}
}
