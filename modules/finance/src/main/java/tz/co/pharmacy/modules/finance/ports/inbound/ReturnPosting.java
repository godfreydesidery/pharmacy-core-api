package tz.co.pharmacy.modules.finance.ports.inbound;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A sale return to post. A refund in a foreign currency names it and the amount paid back; {@code
 * fxDifference} (base paid out − net − tax) is the realised exchange loss (positive) or gain.
 */
public record ReturnPosting(
    UUID returnUid,
    String number,
    UUID branchUid,
    LocalDate date,
    BigDecimal net,
    BigDecimal tax,
    BigDecimal cost,
    String refundMethod,
    String currency,
    BigDecimal foreignAmount,
    BigDecimal fxDifference) {

  /** A refund in the base currency. */
  public ReturnPosting(
      UUID returnUid,
      String number,
      UUID branchUid,
      LocalDate date,
      BigDecimal net,
      BigDecimal tax,
      BigDecimal cost,
      String refundMethod) {
    this(returnUid, number, branchUid, date, net, tax, cost, refundMethod, null, null, null);
  }
}
