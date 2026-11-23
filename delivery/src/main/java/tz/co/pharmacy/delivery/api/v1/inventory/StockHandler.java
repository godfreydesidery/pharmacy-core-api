package tz.co.pharmacy.delivery.api.v1.inventory;

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
import tz.co.pharmacy.delivery.api.Idempotent;
import tz.co.pharmacy.delivery.api.Pages;
import tz.co.pharmacy.modules.inventory.ports.inbound.AdjustStockCommand;
import tz.co.pharmacy.modules.inventory.ports.inbound.AdjustmentReason;
import tz.co.pharmacy.modules.inventory.ports.inbound.BatchView;
import tz.co.pharmacy.modules.inventory.ports.inbound.ExpiringBatchView;
import tz.co.pharmacy.modules.inventory.ports.inbound.MovementType;
import tz.co.pharmacy.modules.inventory.ports.inbound.MovementView;
import tz.co.pharmacy.modules.inventory.ports.inbound.ReceiveStockCommand;
import tz.co.pharmacy.modules.inventory.ports.inbound.SerialStatus;
import tz.co.pharmacy.modules.inventory.ports.inbound.SerialUnitView;
import tz.co.pharmacy.modules.inventory.ports.inbound.StockFilter;
import tz.co.pharmacy.modules.inventory.ports.inbound.StockItemView;
import tz.co.pharmacy.modules.inventory.ports.inbound.StockQueries;
import tz.co.pharmacy.modules.inventory.ports.inbound.StockService;
import tz.co.pharmacy.modules.inventory.ports.inbound.StockStatus;
import tz.co.pharmacy.modules.inventory.ports.inbound.StockSummaryView;
import tz.co.pharmacy.modules.tenancy.ports.inbound.SettingsService;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Page;

/** Stock at a branch: the list, batches, expiry, the ledger, opening stock and adjustments. */
@RestController
@RequestMapping("/api/v1/stock")
@RequiredArgsConstructor
public class StockHandler {

  private final StockQueries stockQueries;
  private final StockService stockService;
  private final SettingsService settingsService;

  @GetMapping
  @Access(scope = AccessScope.BRANCH, permission = "inventory.view")
  public ApiResponse<Page<StockItemView>> list(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID categoryUid,
      @RequestParam(required = false) String productType,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    StockStatus stockStatus =
        status == null || status.isBlank()
            ? null
            : Checks.oneOf(StockStatus.class, status, "status");
    return ApiResponse.ok(
        stockQueries.stock(
            new StockFilter(
                q,
                stockStatus,
                categoryUid,
                productType == null || productType.isBlank()
                    ? null
                    : productType.trim().toUpperCase()),
            Pages.of(cursor, limit)),
        "Stock retrieved");
  }

  /** The stock summary of the request's branch, or of every branch the caller may use. */
  @GetMapping("/summary")
  @Access(scope = AccessScope.TENANT_OPTIONAL_BRANCH, permission = "inventory.view")
  public ApiResponse<StockSummaryView> summary() {
    return ApiResponse.ok(
        stockQueries.summary(
            ExecutionContext.reportBranches(), settingsService.current().expiryAlertDays()),
        "Stock summary retrieved");
  }

  /** The current branch's units of a serial-tracked product, such as each wheelchair. */
  @GetMapping("/{productUid}/serials")
  @Access(scope = AccessScope.BRANCH, permission = "inventory.view")
  public ApiResponse<List<SerialUnitView>> serials(
      @PathVariable UUID productUid, @RequestParam(required = false) String status) {
    return ApiResponse.ok(
        stockQueries.serials(
            productUid,
            status == null || status.isBlank()
                ? null
                : Checks.oneOf(SerialStatus.class, status, "status")),
        "Serial units retrieved");
  }

  @GetMapping("/{productUid}/batches")
  @Access(scope = AccessScope.BRANCH, permission = "inventory.view")
  public ApiResponse<List<BatchView>> batches(@PathVariable UUID productUid) {
    return ApiResponse.ok(stockQueries.batches(productUid), "Batches retrieved");
  }

  /**
   * Units of the product held across the tenant (expired and in transit included): while any are,
   * its tracking mode is locked. For the product form, so it needs catalogue rights only.
   */
  @GetMapping("/{productUid}/held")
  @Access(scope = AccessScope.TENANT_OPTIONAL_BRANCH, permission = "catalogue.view")
  public ApiResponse<Held> held(@PathVariable UUID productUid) {
    return ApiResponse.ok(new Held(stockQueries.unitsHeld(productUid)), "Stock held retrieved");
  }

  public record Held(long unitsHeld) {}

  /** Batches in stock expiring within {@code withinDays} (90 by default), expired ones included. */
  @GetMapping("/expiring")
  @Access(scope = AccessScope.TENANT_OPTIONAL_BRANCH, permission = "inventory.view")
  public ApiResponse<Page<ExpiringBatchView>> expiring(
      @RequestParam(required = false) Integer withinDays,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    return ApiResponse.ok(
        stockQueries.expiring(
            ExecutionContext.reportBranches(),
            withinDays == null ? 90 : withinDays,
            Pages.of(cursor, limit)),
        "Expiring batches retrieved");
  }

  @GetMapping("/movements")
  @Access(scope = AccessScope.BRANCH, permission = "inventory.view")
  public ApiResponse<Page<MovementView>> movements(
      @RequestParam(required = false) UUID productUid,
      @RequestParam(required = false) String type,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    MovementType movementType =
        type == null || type.isBlank() ? null : Checks.oneOf(MovementType.class, type, "type");
    return ApiResponse.ok(
        stockQueries.movements(productUid, movementType, Pages.of(cursor, limit)),
        "Movements retrieved");
  }

  @Idempotent
  @PostMapping("/opening")
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.BRANCH, permission = "inventory.adjust")
  public ApiResponse<MovementView> opening(@RequestBody OpeningStockRequest body) {
    return ApiResponse.created(
        stockService.openingStock(
            new ReceiveStockCommand(
                body.productUid(),
                body.batchNumber(),
                body.expiryDate(),
                body.quantity() == null ? 0 : body.quantity(),
                body.unitCost(),
                MovementType.OPENING,
                "OPENING",
                body.serialNumbers())),
        "Opening stock recorded");
  }

  @Idempotent
  @PostMapping("/adjustments")
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.BRANCH, permission = "inventory.adjust")
  public ApiResponse<MovementView> adjust(@RequestBody AdjustmentRequest body) {
    return ApiResponse.created(
        stockService.adjust(
            new AdjustStockCommand(
                body.productUid(),
                body.batchUid(),
                body.quantityChange() == null ? 0 : body.quantityChange(),
                Checks.oneOf(AdjustmentReason.class, body.reasonCode(), "reasonCode"),
                body.note(),
                body.serialNumbers())),
        "Stock adjusted");
  }

  public record OpeningStockRequest(
      UUID productUid,
      String batchNumber,
      LocalDate expiryDate,
      Integer quantity,
      BigDecimal unitCost,
      List<String> serialNumbers) {}

  public record AdjustmentRequest(
      UUID productUid,
      UUID batchUid,
      Integer quantityChange,
      String reasonCode,
      String note,
      List<String> serialNumbers) {}
}
