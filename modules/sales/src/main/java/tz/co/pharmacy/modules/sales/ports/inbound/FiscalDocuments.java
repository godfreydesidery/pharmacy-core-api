package tz.co.pharmacy.modules.sales.ports.inbound;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;

/**
 * The pharmacy's fiscal documents (STD-1): receipts and credit notes are issued after their sale or
 * return commits ({@link SaleService#fiscalise}, {@link #fiscaliseReturn}); a Z report closes a
 * branch's day. Each document is issued in transactions of its own, with a short lease on it and no
 * transaction open while the authority is asked. All of these do nothing when no fiscal integration
 * is on.
 */
public interface FiscalDocuments {

  /** Whether a fiscal integration is on ({@code pharmacy.integrations.fiscal.provider}). */
  boolean enabled();

  /**
   * Issues a return's credit note when it is still due, after the return committed: as {@link
   * SaleService#fiscalise}. While the sale's own receipt is not issued yet, the credit note waits
   * (it throws, so the {@code sale.returned} reaction is delivered again).
   */
  void fiscaliseReturn(UUID returnUid);

  /**
   * Sends a FAILED receipt or credit note of the current branch again, now (it is PENDING again
   * meanwhile). 404 fiscal_document_not_found; 409 fiscal_document_issued when it is ISSUED.
   */
  FiscalDocumentView retry(UUID documentUid);

  /** The fiscal documents of a sale of the current branch: its receipt and its credit notes. */
  List<FiscalDocumentView> ofSale(UUID saleUid);

  /**
   * Closes a branch's day (the pharmacy's local {@code day}, today or before): one Z report that
   * closes every issued receipt and credit note of the branch on or before that day that no Z
   * report closed yet, then sent to the authority. A branch's day is closed once: asked again, it
   * answers the Z report it has (sending it again while it is PENDING). 422 invalid_day for a day
   * after today. The scheduled job closes every branch's past days that have documents.
   */
  FiscalDocumentView closeDay(UUID branchUid, LocalDate day);

  /**
   * The days of {@code branchUid} (null: every branch) before today that have issued documents no Z
   * report closed yet, oldest first (at most 31): the days the scheduled job closes.
   */
  List<BranchDay> daysToClose(UUID branchUid);

  /** A branch and one of its local days. */
  record BranchDay(UUID branchUid, LocalDate day) {}

  /**
   * Sends again every PENDING document of the pharmacy created before {@code olderThan} ago whose
   * lease is free (at most {@code limit}): those whose event was delivered while the authority
   * failed, and Z reports. Returns how many were issued now.
   */
  int resend(java.time.Duration olderThan, int limit);

  /** Z reports, newest day first: of one branch, or of every branch when null. */
  Page<FiscalDocumentView> zReports(UUID branchUid, PageQuery query);
}
