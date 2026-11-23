package tz.co.pharmacy.platform.approval;

import java.util.Set;

/**
 * The actions a second person approves (the owner's list of 23 Nov 2026). Those in {@link #ALWAYS}
 * need an approval whatever the amount; the others above the pharmacy's approval limit.
 */
public final class ApprovalActions {

  /** A refund (a sale return) above the limit. */
  public static final String REFUND = "REFUND";

  /** A return after the pharmacy's return window. */
  public static final String LATE_RETURN = "LATE_RETURN";

  /** A stock adjustment, destruction or stock-take loss, valued at cost, above the limit. */
  public static final String STOCK_WRITE_OFF = "STOCK_WRITE_OFF";

  /** Any stock written off or destroyed of a controlled medicine. */
  public static final String CONTROLLED_WRITE_OFF = "CONTROLLED_WRITE_OFF";

  /** Opening stock recorded in a branch that has already traded. */
  public static final String LATE_OPENING_STOCK = "LATE_OPENING_STOCK";

  /** A voucher issued above the limit. */
  public static final String VOUCHER = "VOUCHER";

  /** A discount at the till above the pharmacy's maximum discount. */
  public static final String DEEP_DISCOUNT = "DEEP_DISCOUNT";

  /** A promotion deeper than the pharmacy's maximum discount. */
  public static final String DEEP_PROMOTION = "DEEP_PROMOTION";

  /** Moving the books' period lock back to an earlier date (or removing it). */
  public static final String PERIOD_UNLOCK = "PERIOD_UNLOCK";

  /** Changing a medicine's dispensing class (OTC, prescription, controlled). */
  public static final String SCHEDULE_CHANGE = "SCHEDULE_CHANGE";

  /** Writing off a customer's debt. */
  public static final String CREDIT_WRITE_OFF = "CREDIT_WRITE_OFF";

  /** Closing a till shift whose cash is short or over by more than the limit. */
  public static final String SHIFT_VARIANCE = "SHIFT_VARIANCE";

  /** Stock a transfer lost on the way, at cost, above the limit. */
  public static final String TRANSFER_SHORTFALL = "TRANSFER_SHORTFALL";

  public static final Set<String> ALWAYS =
      Set.of(
          LATE_RETURN,
          CONTROLLED_WRITE_OFF,
          LATE_OPENING_STOCK,
          DEEP_DISCOUNT,
          DEEP_PROMOTION,
          PERIOD_UNLOCK,
          SCHEDULE_CHANGE,
          CREDIT_WRITE_OFF);

  public static final Set<String> ABOVE_LIMIT =
      Set.of(REFUND, STOCK_WRITE_OFF, VOUCHER, SHIFT_VARIANCE, TRANSFER_SHORTFALL);

  private ApprovalActions() {}

  public static boolean known(String action) {
    return ALWAYS.contains(action) || ABOVE_LIMIT.contains(action);
  }
}
