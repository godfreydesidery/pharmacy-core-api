package tz.co.pharmacy.delivery.api.v1.sales;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.delivery.api.Pages;
import tz.co.pharmacy.modules.sales.ports.inbound.FiscalDocumentView;
import tz.co.pharmacy.modules.sales.ports.inbound.FiscalDocuments;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * Fiscal documents (STD-1): a sale's receipt and credit notes, sending a FAILED one again, and the
 * branches' daily Z reports, listed and closed by hand (the fiscal job closes past days itself).
 */
@RestController
@RequestMapping("/api/v1")
public class FiscalHandler {

  private final FiscalDocuments documents;

  public FiscalHandler(FiscalDocuments documents) {
    this.documents = documents;
  }

  /** A sale's fiscal documents at the current branch: its receipt, then its credit notes. */
  @GetMapping("/sales/{uid}/fiscal-documents")
  @Access(scope = AccessScope.BRANCH, permission = "sales.view")
  public ApiResponse<List<FiscalDocumentView>> ofSale(@PathVariable UUID uid) {
    return ApiResponse.ok(documents.ofSale(uid), "Fiscal documents retrieved");
  }

  /**
   * Sends a receipt or credit note of the current branch again, now: one the authority refused
   * (FAILED), or one still PENDING. 404 fiscal_document_not_found, 409 fiscal_document_issued, 422
   * fiscal_not_enabled.
   */
  @PostMapping("/fiscal/documents/{uid}/retry")
  @Access(scope = AccessScope.BRANCH, permission = "sales.sell")
  public ApiResponse<FiscalDocumentView> retry(@PathVariable UUID uid) {
    return ApiResponse.ok(documents.retry(uid), "Fiscal document sent");
  }

  /** The Z reports, newest first: of one branch, else of every branch the caller reports on. */
  @GetMapping("/fiscal/z-reports")
  @Access(scope = AccessScope.TENANT_OPTIONAL_BRANCH, permission = "finance.view")
  public ApiResponse<Page<FiscalDocumentView>> zReports(
      @RequestParam(required = false) UUID branchUid,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    return ApiResponse.ok(
        documents.zReports(branchUid, Pages.of(cursor, limit)), "Z reports retrieved");
  }

  /**
   * Closes a branch's day now (today or a day before it): its Z report, sent to the authority. A
   * day closed already answers its report. 422 invalid_day (a day after today), fiscal_not_enabled;
   * 403 branch_not_allowed for a branch the caller may not report on.
   */
  @PostMapping("/fiscal/z-reports")
  @Access(scope = AccessScope.TENANT_OPTIONAL_BRANCH, permission = "finance.manage")
  public ApiResponse<FiscalDocumentView> closeDay(@RequestBody CloseDayRequest body) {
    if (body == null || body.branchUid() == null) {
      throw Refusal.invalid("branch_required", "Name the branch whose day closes.", "branchUid");
    }
    Set<UUID> allowed = ExecutionContext.reportBranches();
    if (allowed != null && !allowed.contains(body.branchUid())) {
      throw Refusal.forbidden("branch_not_allowed", "You may not close this branch's day.");
    }
    return ApiResponse.ok(documents.closeDay(body.branchUid(), body.day()), "Day closed");
  }

  /** {@code day}: the pharmacy's local day to close, today when left out. */
  public record CloseDayRequest(UUID branchUid, LocalDate day) {}
}
