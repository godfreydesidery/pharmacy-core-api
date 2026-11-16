package tz.co.pharmacy.modules.finance.ports.inbound;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Opening balances in the base currency as of {@code asOf}: money held, stock at cost, and per
 * party what customers owe ({@code receivables}) and what is owed to suppliers ({@code payables}).
 * Owner's equity is the difference.
 */
public record OpeningBalancesCommand(
    LocalDate asOf,
    BigDecimal cash,
    BigDecimal bank,
    BigDecimal mobileMoney,
    BigDecimal inventory,
    List<Party> receivables,
    List<Party> payables) {

  /** A customer or supplier, named as the caller resolved it. */
  public record Party(UUID uid, String name, BigDecimal amount) {}
}
