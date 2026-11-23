package tz.co.pharmacy.modules.inventory.ports.inbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;

/**
 * Stock-takes at the current branch: a count sheet of every batch on hand, the quantities counted,
 * and posting the differences as count corrections. Serial-numbered products are not on the sheet
 * (they are checked unit by unit).
 */
public interface StockCounts {

  StockCountView start(String note);

  Page<StockCountView> list(PageQuery query);

  /**
   * The count as the counter sees it. While it is OPEN the count is blind (FRD-12): no line shows
   * its expected quantity or variance, nor the count its variance or loss.
   */
  StockCountView get(UUID uid);

  /**
   * The count with expected quantities, variances and the loss at cost, for whoever reviews it
   * before posting (the route needs inventory.adjust).
   */
  StockCountView review(UUID uid);

  /** Records counted quantities; a null count leaves the line uncounted. 422 count_posted. */
  StockCountView record(UUID uid, List<CountEntry> entries);

  /**
   * Posts every counted line whose count differs from the book quantity as a COUNT_CORRECTION
   * adjustment: counted − (expected + what the batch's stock moved at the branch since the sheet),
   * so sales and receipts during the count are not taken for losses or gains. Uncounted lines are
   * left alone. A view's variance is reckoned the same way. 422 count_posted. A loss in total at
   * cost above the pharmacy's limit needs a manager (STOCK_WRITE_OFF); any loss of a controlled
   * medicine always does (CONTROLLED_WRITE_OFF), for the count's whole loss.
   */
  StockCountView post(UUID uid);

  record CountEntry(UUID lineUid, Integer counted) {}

  record LineView(
      UUID uid,
      UUID productUid,
      String productName,
      String batchNumber,
      LocalDate expiryDate,
      Integer expected,
      Integer counted,
      Integer variance,
      BigDecimal varianceValue) {}

  /**
   * {@code blind}: the expected quantities, variances, {@code varianceValue} (net, at cost) and
   * {@code lossValue} (the shortages alone, at cost) are hidden (null).
   */
  record StockCountView(
      UUID uid,
      String number,
      String status,
      String note,
      int lines,
      int counted,
      BigDecimal varianceValue,
      BigDecimal lossValue,
      boolean blind,
      Instant createdAt,
      String createdBy,
      Instant postedAt,
      String postedBy,
      List<LineView> items) {}
}
