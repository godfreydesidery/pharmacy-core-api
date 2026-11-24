package tz.co.pharmacy.delivery.api.v1.finance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.modules.access.ports.inbound.Permissions;
import tz.co.pharmacy.modules.purchasing.ports.inbound.SupplierDebitNotes;
import tz.co.pharmacy.modules.receivables.ports.inbound.CustomerCredit;
import tz.co.pharmacy.modules.sales.ports.inbound.DebitNoteView;
import tz.co.pharmacy.modules.sales.ports.inbound.SaleDebitNotes;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * Debit notes (STD-2, owner's decision of 24 Nov 2026; the accountant confirms the method). On a
 * sale: the customer is charged more after the invoice, with VAT, on their account (their statement
 * shows the charge under the note's number), and the books post Dr 1100 / Cr 4000, 2100. From a
 * supplier: what is owed for a goods receipt rises, with its input VAT, and the books post Dr 5120,
 * 1150 / Cr 2000. Both are book-keeping corrections, so they need {@code finance.manage}.
 */
@RestController
@RequestMapping("/api/v1")
public class DebitNoteHandler {

  private final SaleDebitNotes sales;
  private final SupplierDebitNotes suppliers;
  private final CustomerCredit accounts;

  public DebitNoteHandler(
      SaleDebitNotes sales, SupplierDebitNotes suppliers, CustomerCredit accounts) {
    this.sales = sales;
    this.suppliers = suppliers;
    this.accounts = accounts;
  }

  /** A debit note on a sale, charged to its customer's account in the same transaction. */
  @PostMapping("/sales/{saleUid}/debit-notes")
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.TENANT, permission = Permissions.FINANCE_MANAGE)
  @Transactional
  public ApiResponse<DebitNoteView> saleDebitNote(
      @PathVariable UUID saleUid, @RequestBody SaleDebitNoteRequest body) {
    if (body == null) {
      throw Refusal.malformed("An amount and a reason are required.");
    }
    DebitNoteView note = sales.issue(saleUid, body.netAmount(), body.taxCategory(), body.reason());
    accounts.charge(note.customerUid(), note.saleUid(), note.number(), note.total(), null);
    return ApiResponse.ok(note, "Debit note issued");
  }

  @GetMapping("/sales/{saleUid}/debit-notes")
  @Access(scope = AccessScope.TENANT, permission = Permissions.FINANCE_VIEW)
  public ApiResponse<List<DebitNoteView>> saleDebitNotes(@PathVariable UUID saleUid) {
    return ApiResponse.ok(sales.ofSale(saleUid), "Debit notes retrieved");
  }

  /** A supplier's debit note on a goods receipt: more is owed to them. */
  @PostMapping("/goods-receipts/{receiptUid}/debit-notes")
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.TENANT, permission = Permissions.FINANCE_MANAGE)
  public ApiResponse<SupplierDebitNotes.DebitNoteView> supplierDebitNote(
      @PathVariable UUID receiptUid, @RequestBody SupplierDebitNoteRequest body) {
    if (body == null) {
      throw Refusal.malformed("An amount and a reason are required.");
    }
    return ApiResponse.ok(
        suppliers.record(
            receiptUid,
            body.netAmount(),
            body.vatAmount(),
            body.reason(),
            body.supplierReference()),
        "Supplier debit note recorded");
  }

  @GetMapping("/goods-receipts/{receiptUid}/debit-notes")
  @Access(scope = AccessScope.TENANT, permission = Permissions.FINANCE_VIEW)
  public ApiResponse<List<SupplierDebitNotes.DebitNoteView>> supplierDebitNotes(
      @PathVariable UUID receiptUid) {
    return ApiResponse.ok(suppliers.ofReceipt(receiptUid), "Supplier debit notes retrieved");
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SaleDebitNoteRequest(BigDecimal netAmount, String taxCategory, String reason) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SupplierDebitNoteRequest(
      BigDecimal netAmount, BigDecimal vatAmount, String reason, String supplierReference) {}
}
