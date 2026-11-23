package tz.co.pharmacy.modules.sales.ports.inbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;

/** Till shifts of the current cashier at the current branch, and their close (Z) reports. */
public interface Shifts {

  /** The caller's open shift here, with its figures so far (an X report). */
  Optional<ShiftView> current();

  /** Opens a shift with a cash float; 409 shift_open if one is already open. */
  ShiftView open(BigDecimal openingFloat);

  /** Closes the caller's open shift with the cash counted; 422 no_open_shift. */
  ShiftView close(BigDecimal countedCash, String note);

  /** The branch's recent shifts. */
  Page<ShiftView> list(PageQuery query);

  /**
   * The cash counted when the branch's last shift closed, to suggest the next float; empty before
   * the first close.
   */
  Optional<LastCount> lastCount();

  record LastCount(BigDecimal countedCash, Instant closedAt, String cashierName) {}

  /**
   * {@code tenders} are the shift's takings by method in the base currency; {@code expectedCash} =
   * float + cash taken − change − cash refunds; {@code variance} = counted − expected.
   */
  record ShiftView(
      UUID uid,
      String cashier,
      String cashierName,
      String status,
      Instant openedAt,
      Instant closedAt,
      BigDecimal openingFloat,
      long sales,
      BigDecimal salesTotal,
      Map<String, BigDecimal> tenders,
      BigDecimal changeGiven,
      BigDecimal cashRefunds,
      BigDecimal voucherCash,
      BigDecimal expectedCash,
      BigDecimal countedCash,
      BigDecimal variance,
      String note) {}
}
