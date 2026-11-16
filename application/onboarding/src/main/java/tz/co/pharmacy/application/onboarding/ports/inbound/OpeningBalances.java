package tz.co.pharmacy.application.onboarding.ports.inbound;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import tz.co.pharmacy.modules.finance.ports.inbound.OpeningBalanceView;

/**
 * Setting up a pharmacy's books: posts its opening balances (finance) and opens each customer's
 * account with what they owe (receivables), once per tenant. Inventory defaults to the stock on
 * hand at cost across every branch.
 */
public interface OpeningBalances {

  /** 409 opening_balances_posted; 404 customer_not_found / supplier_not_found. */
  OpeningBalanceView post(Command command);

  Optional<OpeningBalanceView> current();

  /** The stock on hand at cost across every branch, as the inventory figure would default to. */
  BigDecimal stockAtCost();

  /** {@code inventory} null takes the stock at cost. */
  record Command(
      LocalDate asOf,
      BigDecimal cash,
      BigDecimal bank,
      BigDecimal mobileMoney,
      BigDecimal inventory,
      List<Amount> receivables,
      List<Amount> payables) {}

  /** A customer's (receivables) or supplier's (payables) opening amount. */
  record Amount(UUID partyUid, BigDecimal amount) {}
}
