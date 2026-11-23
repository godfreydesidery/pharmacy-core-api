package tz.co.pharmacy.delivery.api.v1.sales;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.application.checkout.ports.inbound.Checkout;
import tz.co.pharmacy.application.checkout.ports.inbound.CheckoutCommand;
import tz.co.pharmacy.application.checkout.ports.inbound.CheckoutLine;
import tz.co.pharmacy.application.checkout.ports.inbound.SellableProduct;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.delivery.api.Idempotent;
import tz.co.pharmacy.delivery.api.Pages;
import tz.co.pharmacy.modules.sales.ports.inbound.PaymentCommand;
import tz.co.pharmacy.modules.sales.ports.inbound.PaymentMethod;
import tz.co.pharmacy.modules.sales.ports.inbound.PrescriptionDetails;
import tz.co.pharmacy.modules.sales.ports.inbound.RecordReturnCommand;
import tz.co.pharmacy.modules.sales.ports.inbound.ReturnDisposition;
import tz.co.pharmacy.modules.sales.ports.inbound.ReturnLineCommand;
import tz.co.pharmacy.modules.sales.ports.inbound.SaleFilter;
import tz.co.pharmacy.modules.sales.ports.inbound.SaleReturnView;
import tz.co.pharmacy.modules.sales.ports.inbound.SaleService;
import tz.co.pharmacy.modules.sales.ports.inbound.SaleStatus;
import tz.co.pharmacy.modules.sales.ports.inbound.SaleSummaryView;
import tz.co.pharmacy.modules.sales.ports.inbound.SaleView;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.Refusal;

/** The POS and sales history of the branch named by X-Branch-Id (contract section 11). */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class SalesHandler {

  private final Checkout checkout;
  private final SaleService saleService;

  /** Active products with their price and quantity on hand here, such as {@code ?q=para}. */
  @GetMapping("/pos/products")
  @Access(scope = AccessScope.BRANCH, permission = "sales.sell")
  public ApiResponse<Page<SellableProduct>> sellable(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) UUID categoryUid,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    return ApiResponse.ok(
        checkout.sellable(q, categoryUid, Pages.of(cursor, limit)), "Products retrieved");
  }

  /** Sells the cart. The Idempotency-Key header, when sent, wins over the body's key. */
  @PostMapping("/sales")
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.BRANCH, permission = "sales.sell")
  public ApiResponse<SaleView> checkout(
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKeyHeader,
      @RequestBody CheckoutRequest body) {
    if (body == null) {
      throw Refusal.malformed("The request needs a body.");
    }
    String key =
        idempotencyKeyHeader != null && !idempotencyKeyHeader.isBlank()
            ? idempotencyKeyHeader
            : body.idempotencyKey();
    List<CheckoutLine> lines =
        body.lines() == null
            ? List.of()
            : body.lines().stream()
                .map(
                    line ->
                        new CheckoutLine(
                            line.productUid(),
                            line.quantity() == null ? 0 : line.quantity(),
                            line.serialNumbers()))
                .toList();
    List<PaymentCommand> payments = new ArrayList<>();
    if (body.payments() != null) {
      for (int i = 0; i < body.payments().size(); i++) {
        PaymentRequest payment = body.payments().get(i);
        payments.add(
            new PaymentCommand(
                Checks.oneOf(PaymentMethod.class, payment.method(), "payments[" + i + "].method"),
                payment.amount(),
                payment.reference(),
                payment.currency(),
                payment.exchangeRate(),
                payment.channel()));
      }
    }
    return ApiResponse.created(
        checkout.checkout(
            new CheckoutCommand(
                key,
                body.customerUid(),
                body.discountPercent(),
                lines,
                payments,
                body.prescription(),
                body.memberUid(),
                body.preauthNumber(),
                body.diagnosisCode(),
                body.balanceToAccount(),
                body.excessToAccount(),
                body.pricePlan())),
        "Sale completed");
  }

  /**
   * The branch's sales, newest first, such as {@code ?q=INV-HQ&from=2026-11-01}; {@code
   * since=2026-11-21T09:30:00Z} keeps those made from that moment (the last 24 hours).
   */
  @GetMapping("/sales")
  @Access(scope = AccessScope.BRANCH, permission = "sales.view")
  public ApiResponse<Page<SaleSummaryView>> list(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          java.time.Instant since,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    SaleStatus saleStatus =
        status == null || status.isBlank()
            ? null
            : Checks.oneOf(SaleStatus.class, status, "status");
    return ApiResponse.ok(
        saleService.list(new SaleFilter(q, saleStatus, from, to, since), Pages.of(cursor, limit)),
        "Sales retrieved");
  }

  @GetMapping("/sales/{uid}")
  @Access(scope = AccessScope.BRANCH, permission = "sales.view")
  public ApiResponse<SaleView> get(@PathVariable UUID uid) {
    // A sale of another branch is not found: a user limited to one branch sees only its sales.
    UUID branch = ExecutionContext.requireBranch();
    return ApiResponse.ok(
        saleService
            .find(uid)
            .filter(sale -> branch.equals(sale.branchUid()))
            .orElseThrow(() -> Refusal.notFound("sale_not_found", "No such sale.")),
        "Sale retrieved");
  }

  /** Units of a sale coming back; they return to the batch they were sold from. */
  @Idempotent
  @PostMapping("/sales/{uid}/returns")
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.BRANCH, permission = "sales.refund")
  public ApiResponse<SaleReturnView> returnSale(
      @PathVariable UUID uid, @RequestBody ReturnRequest body) {
    if (body == null) {
      throw Refusal.malformed("The request needs a body.");
    }
    List<ReturnLineCommand> lines =
        body.lines() == null
            ? List.of()
            : body.lines().stream()
                .map(
                    line ->
                        new ReturnLineCommand(
                            line.saleLineUid(),
                            line.quantity() == null ? 0 : line.quantity(),
                            line.serialNumbers(),
                            line.disposition() == null || line.disposition().isBlank()
                                ? null
                                : Checks.oneOf(
                                    ReturnDisposition.class,
                                    line.disposition(),
                                    "lines[].disposition")))
                .toList();
    return ApiResponse.created(
        checkout.returnSale(
            new RecordReturnCommand(
                uid,
                body.reason(),
                body.refundMethod() == null || body.refundMethod().isBlank()
                    ? null
                    : Checks.oneOf(PaymentMethod.class, body.refundMethod(), "refundMethod"),
                lines,
                body.refundCurrency())),
        "Sale return recorded");
  }

  /**
   * Releases what a return line holds in quarantine: {@code outcome} SHELF (back to sellable stock;
   * an OTC medicine only) or DAMAGED (written off at cost).
   */
  @Idempotent
  @PostMapping("/sales/returns/{returnUid}/lines/{lineUid}/release")
  @Access(scope = AccessScope.BRANCH, permission = "sales.refund")
  public ApiResponse<SaleReturnView> release(
      @PathVariable UUID returnUid,
      @PathVariable UUID lineUid,
      @RequestBody(required = false) ReleaseRequest body) {
    String outcome = body == null ? null : body.outcome();
    return ApiResponse.ok(
        checkout.releaseReturnLine(
            returnUid,
            lineUid,
            outcome == null || outcome.isBlank()
                ? null
                : Checks.oneOf(ReturnDisposition.class, outcome, "outcome")),
        "Released from quarantine");
  }

  /** The branch's returns with units still held in quarantine, newest first. */
  @GetMapping("/sale-returns/held")
  @Access(scope = AccessScope.BRANCH, permission = "sales.view")
  public ApiResponse<List<SaleReturnView>> heldReturns() {
    return ApiResponse.ok(saleService.heldReturns(), "Held returns retrieved");
  }

  @GetMapping("/sale-returns")
  @Access(scope = AccessScope.BRANCH, permission = "sales.view")
  public ApiResponse<Page<SaleReturnView>> returns(
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    return ApiResponse.ok(saleService.returns(Pages.of(cursor, limit)), "Sale returns retrieved");
  }

  public record CheckoutRequest(
      String idempotencyKey,
      UUID customerUid,
      BigDecimal discountPercent,
      List<LineRequest> lines,
      List<PaymentRequest> payments,
      PrescriptionDetails prescription,
      UUID memberUid,
      String preauthNumber,
      String diagnosisCode,
      Boolean balanceToAccount,
      Boolean excessToAccount,
      /* NORMAL or a pricing plan's uid; empty for the customer's own plan. */
      String pricePlan) {}

  /** {@code serialNumbers}: the units sold of a serial-tracked product, one per unit. */
  public record LineRequest(UUID productUid, Integer quantity, List<String> serialNumbers) {}

  /** {@code currency} defaults to the base; {@code exchangeRate} to today's rate. */
  public record PaymentRequest(
      String method,
      BigDecimal amount,
      String reference,
      String currency,
      BigDecimal exchangeRate,
      String channel) {}

  /** {@code refundCurrency}: a foreign currency the sale was tendered in; omitted for the base. */
  public record ReturnRequest(
      String reason, String refundMethod, List<ReturnLineRequest> lines, String refundCurrency) {}

  /**
   * {@code serialNumbers}: the units returned of a serial-tracked line; else the first sold. {@code
   * disposition}: SHELF, DAMAGED or QUARANTINE; omitted, SHELF for an OTC medicine and QUARANTINE
   * for a prescription or controlled one (which may not go to SHELF).
   */
  public record ReturnLineRequest(
      UUID saleLineUid, Integer quantity, List<String> serialNumbers, String disposition) {}

  /** {@code outcome}: SHELF or DAMAGED. */
  public record ReleaseRequest(String outcome) {}
}
