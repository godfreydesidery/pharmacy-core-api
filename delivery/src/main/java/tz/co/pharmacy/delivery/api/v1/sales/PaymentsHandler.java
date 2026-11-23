package tz.co.pharmacy.delivery.api.v1.sales;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
import tz.co.pharmacy.modules.integrations.ports.inbound.Payments;
import tz.co.pharmacy.modules.integrations.ports.inbound.Payments.IntentView;
import tz.co.pharmacy.modules.integrations.ports.inbound.Payments.MethodTypeCommand;
import tz.co.pharmacy.modules.integrations.ports.inbound.Payments.MethodTypeView;
import tz.co.pharmacy.modules.integrations.ports.inbound.Payments.RequestPaymentCommand;
import tz.co.pharmacy.modules.integrations.ports.inbound.Payments.TenantMethodCommand;
import tz.co.pharmacy.modules.integrations.ports.inbound.Payments.TenantMethodView;
import tz.co.pharmacy.modules.sales.ports.inbound.PaymentMethod;
import tz.co.pharmacy.modules.sales.ports.inbound.Vouchers;
import tz.co.pharmacy.modules.sales.ports.inbound.Vouchers.IssueVoucherCommand;
import tz.co.pharmacy.modules.sales.ports.inbound.Vouchers.VoucherView;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * Payment methods: the platform registry (root administrator), the methods a pharmacy accepts, what
 * the till offers, requests pushed to a customer's phone, and vouchers.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class PaymentsHandler {

  private final Payments payments;
  private final Vouchers vouchers;

  @GetMapping("/platform/payment-methods")
  @Access(scope = AccessScope.PLATFORM, permission = "platform.manage")
  public ApiResponse<?> registry() {
    return ApiResponse.ok(Pages.slice(payments.methodTypes()), "Payment methods retrieved");
  }

  @PutMapping("/platform/payment-methods")
  @Access(scope = AccessScope.PLATFORM, permission = "platform.manage")
  public ApiResponse<MethodTypeView> saveRegistry(@RequestBody MethodTypeCommand body) {
    if (body == null) {
      throw Refusal.malformed("The request needs a body.");
    }
    return ApiResponse.ok(payments.saveMethodType(body), "Payment method saved");
  }

  @GetMapping("/payment-methods")
  @Access(scope = AccessScope.TENANT, permission = "settings.manage")
  public ApiResponse<?> tenantMethods() {
    return ApiResponse.ok(Pages.slice(payments.tenantMethods()), "Payment methods retrieved");
  }

  @PutMapping("/payment-methods")
  @Access(scope = AccessScope.TENANT, permission = "settings.manage")
  public ApiResponse<TenantMethodView> saveTenantMethod(@RequestBody TenantMethodCommand body) {
    if (body == null) {
      throw Refusal.malformed("The request needs a body.");
    }
    return ApiResponse.ok(payments.saveTenantMethod(body), "Payment method saved");
  }

  @GetMapping("/pos/payment-methods")
  @Access(scope = AccessScope.BRANCH, permission = "sales.sell")
  public ApiResponse<List<TenantMethodView>> tillMethods() {
    return ApiResponse.ok(payments.tillMethods(), "Payment methods retrieved");
  }

  @PostMapping("/pos/payment-requests")
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.BRANCH, permission = "sales.sell")
  public ApiResponse<IntentView> requestPayment(@RequestBody RequestPaymentCommand body) {
    if (body == null) {
      throw Refusal.malformed("The request needs a body.");
    }
    return ApiResponse.created(payments.requestPayment(body), "Payment request sent");
  }

  @GetMapping("/pos/payment-requests/{uid}")
  @Access(scope = AccessScope.BRANCH, permission = "sales.sell")
  public ApiResponse<IntentView> paymentRequest(@PathVariable UUID uid) {
    return ApiResponse.ok(payments.paymentRequest(uid), "Payment request retrieved");
  }

  @GetMapping("/vouchers")
  @Access(scope = AccessScope.TENANT_OPTIONAL_BRANCH, permission = "sales.view")
  public ApiResponse<Page<VoucherView>> vouchers(
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    return ApiResponse.ok(vouchers.list(Pages.of(cursor, limit)), "Vouchers retrieved");
  }

  public record VoucherRequest(
      String code, BigDecimal value, LocalDate expiresOn, String holderName, String paidBy) {}

  @PostMapping("/vouchers")
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.BRANCH, permission = "sales.voucher")
  public ApiResponse<VoucherView> issue(@RequestBody VoucherRequest body) {
    if (body == null) {
      throw Refusal.malformed("The request needs a body.");
    }
    return ApiResponse.created(
        vouchers.issue(
            new IssueVoucherCommand(
                body.code(),
                body.value(),
                body.expiresOn(),
                body.holderName(),
                body.paidBy() == null || body.paidBy().isBlank()
                    ? null
                    : Checks.oneOf(PaymentMethod.class, body.paidBy(), "paidBy"))),
        "Voucher issued");
  }

  @PostMapping("/vouchers/{uid}/void")
  @Access(scope = AccessScope.TENANT_OPTIONAL_BRANCH, permission = "sales.refund")
  public ApiResponse<VoucherView> voidVoucher(@PathVariable UUID uid) {
    return ApiResponse.ok(vouchers.voidVoucher(uid), "Voucher voided");
  }
}
