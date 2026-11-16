package tz.co.pharmacy.delivery.api.v1.inventory;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.modules.inventory.ports.inbound.Recalls;
import tz.co.pharmacy.modules.inventory.ports.inbound.Recalls.RecallBatch;
import tz.co.pharmacy.modules.partners.ports.inbound.CustomerService;
import tz.co.pharmacy.modules.partners.ports.inbound.CustomerView;
import tz.co.pharmacy.modules.sales.ports.inbound.SalesReports;
import tz.co.pharmacy.modules.sales.ports.inbound.SalesReports.BatchSale;

/**
 * Batch recalls: every sale that took the batch, with the customer's phone for contacting them, and
 * quarantine of what is left (across the tenant) until it is released.
 */
@RestController
@RequestMapping("/api/v1/recalls")
@RequiredArgsConstructor
public class RecallHandler {

  private final Recalls recalls;
  private final SalesReports salesReports;
  private final CustomerService customers;

  public record QuarantineRequest(String reason) {}

  public record SaleOfBatch(
      UUID saleUid,
      String saleNumber,
      Instant soldAt,
      String branchName,
      UUID customerUid,
      String customerName,
      String customerPhone,
      String patientName,
      int quantity,
      int returnedQuantity) {}

  public record RecallView(RecallBatch batch, List<SaleOfBatch> sales, long unitsWithCustomers) {}

  @GetMapping("/products/{productUid}/batches")
  @Access(scope = AccessScope.TENANT_OPTIONAL_BRANCH, permission = "inventory.view")
  public ApiResponse<List<RecallBatch>> batches(@PathVariable UUID productUid) {
    return ApiResponse.ok(recalls.batches(productUid), "Batches retrieved");
  }

  @GetMapping("/batches/{batchUid}")
  @Access(scope = AccessScope.TENANT_OPTIONAL_BRANCH, permission = "inventory.view")
  public ApiResponse<RecallView> recall(@PathVariable UUID batchUid) {
    return ApiResponse.ok(view(recalls.batch(batchUid)), "Recall retrieved");
  }

  @PostMapping("/batches/{batchUid}/quarantine")
  @Access(scope = AccessScope.TENANT_OPTIONAL_BRANCH, permission = "inventory.adjust")
  public ApiResponse<RecallView> quarantine(
      @PathVariable UUID batchUid, @RequestBody(required = false) QuarantineRequest body) {
    return ApiResponse.ok(
        view(recalls.quarantine(batchUid, body == null ? null : body.reason())),
        "Batch quarantined");
  }

  @PostMapping("/batches/{batchUid}/release")
  @Access(scope = AccessScope.TENANT_OPTIONAL_BRANCH, permission = "inventory.adjust")
  public ApiResponse<RecallView> release(@PathVariable UUID batchUid) {
    return ApiResponse.ok(view(recalls.release(batchUid)), "Batch released");
  }

  private RecallView view(RecallBatch batch) {
    Map<UUID, String> phones = new HashMap<>();
    List<SaleOfBatch> sales =
        salesReports.batchSales(batch.uid()).stream().map(sale -> sale(sale, phones)).toList();
    long out = sales.stream().mapToLong(s -> s.quantity() - s.returnedQuantity()).sum();
    return new RecallView(batch, sales, out);
  }

  private SaleOfBatch sale(BatchSale sale, Map<UUID, String> phones) {
    String phone =
        sale.customerUid() == null
            ? null
            : phones.computeIfAbsent(
                sale.customerUid(),
                uid -> customers.find(uid).map(CustomerView::phone).orElse(null));
    return new SaleOfBatch(
        sale.saleUid(),
        sale.saleNumber(),
        sale.soldAt(),
        sale.branchName(),
        sale.customerUid(),
        sale.customerName(),
        phone,
        sale.patientName(),
        sale.quantity(),
        sale.returnedQuantity());
  }
}
