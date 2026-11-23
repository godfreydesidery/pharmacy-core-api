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
import tz.co.pharmacy.application.receiving.ports.inbound.Receiving;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.delivery.api.Idempotent;
import tz.co.pharmacy.delivery.api.Pages;
import tz.co.pharmacy.modules.purchasing.ports.inbound.GoodsReceiptService;
import tz.co.pharmacy.modules.purchasing.ports.inbound.GoodsReceiptView;
import tz.co.pharmacy.modules.purchasing.ports.inbound.ReceiptLineCommand;
import tz.co.pharmacy.modules.purchasing.ports.inbound.RecordReceiptCommand;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.Refusal;

/** Goods receipts of the request's branch; recording one brings its stock in. */
@RestController
@RequestMapping("/api/v1/goods-receipts")
@RequiredArgsConstructor
public class GoodsReceiptHandler {

  private final GoodsReceiptService goodsReceipts;
  private final Receiving receiving;

  @GetMapping
  @Access(scope = AccessScope.BRANCH, permission = "purchasing.view")
  public ApiResponse<Page<GoodsReceiptView>> list(
      @RequestParam(required = false) UUID supplierUid,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    return ApiResponse.ok(
        goodsReceipts.list(supplierUid, Pages.of(cursor, limit)), "Goods receipts retrieved");
  }

  @GetMapping("/{goodsReceiptUid}")
  @Access(scope = AccessScope.BRANCH, permission = "purchasing.view")
  public ApiResponse<GoodsReceiptView> get(@PathVariable UUID goodsReceiptUid) {
    return ApiResponse.ok(
        goodsReceipts
            .find(goodsReceiptUid)
            .orElseThrow(
                () -> Refusal.notFound("goods_receipt_not_found", "No such goods receipt.")),
        "Goods receipt retrieved");
  }

  @Idempotent
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.BRANCH, permission = "purchasing.receive")
  public ApiResponse<GoodsReceiptView> receive(@RequestBody GoodsReceiptRequest body) {
    List<ReceiptLineCommand> lines =
        body.lines() == null
            ? List.of()
            : body.lines().stream()
                .map(
                    line ->
                        new ReceiptLineCommand(
                            line.productUid(),
                            line.batchNumber(),
                            line.expiryDate(),
                            line.quantity() == null ? 0 : line.quantity(),
                            line.unitCost(),
                            line.serialNumbers()))
                .toList();
    return ApiResponse.created(
        receiving.receive(
            new RecordReceiptCommand(
                body.purchaseOrderUid(),
                body.supplierUid(),
                body.supplierInvoiceNumber(),
                body.note(),
                lines,
                body.currency(),
                body.exchangeRate())),
        "Goods received");
  }

  public record GoodsReceiptRequest(
      UUID purchaseOrderUid,
      UUID supplierUid,
      String supplierInvoiceNumber,
      String note,
      List<ReceiptLineRequest> lines,
      /* Null means the order's currency, or the base without an order. */
      String currency,
      /* Null means the order's locked rate, or today's rate. */
      BigDecimal exchangeRate) {}

  public record ReceiptLineRequest(
      UUID productUid,
      String batchNumber,
      LocalDate expiryDate,
      Integer quantity,
      BigDecimal unitCost,
      List<String> serialNumbers) {}
}
