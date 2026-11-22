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

  StockCountView get(UUID uid);

  /** Records counted quantities; a null count leaves the line uncounted. 422 count_posted. */
  StockCountView record(UUID uid, List<CountEntry> entries);

  /**
   * Posts every counted line whose count differs from expected as a COUNT_CORRECTION adjustment;
   * uncounted lines are left alone. 422 count_posted.
   */
  StockCountView post(UUID uid);

  record CountEntry(UUID lineUid, Integer counted) {}

  record LineView(
      UUID uid,
      UUID productUid,
      String productName,
      String batchNumber,
      LocalDate expiryDate,
      int expected,
      Integer counted,
      Integer variance,
      BigDecimal varianceValue) {}

  record StockCountView(
      UUID uid,
      String number,
      String status,
      String note,
      int lines,
      int counted,
      BigDecimal varianceValue,
      Instant createdAt,
      String createdBy,
      Instant postedAt,
      String postedBy,
      List<LineView> items) {}
}
