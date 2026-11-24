package tz.co.pharmacy.modules.finance.ports.inbound;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Statements read from the ledger, in the base currency, for the whole tenant (every branch).
 * Balances are on each account's normal side: debit for assets and expenses, credit for the rest.
 */
public interface FinancialStatements {

  /** Each account's debits, credits and balance from all entries up to {@code asOf}. */
  TrialBalance trialBalance(LocalDate asOf);

  /**
   * Assets = liabilities + equity at {@code asOf}; earnings not yet closed (revenue less expenses)
   * are shown under equity as current earnings.
   */
  BalanceSheet balanceSheet(LocalDate asOf);

  /**
   * The VAT posted between the dates (STD-2): output VAT charged on sales (2100 credits), VAT given
   * back on returns (2100 debits), input VAT on purchases and expenses (1150, net of any reversal),
   * and what is payable to TRA: output less returns less input (negative: a credit to carry or
   * claim back).
   */
  VatSummary vat(LocalDate from, LocalDate to);

  record Line(
      String code,
      String name,
      AccountType type,
      BigDecimal debit,
      BigDecimal credit,
      BigDecimal balance) {}

  record TrialBalance(
      LocalDate asOf, List<Line> lines, BigDecimal totalDebit, BigDecimal totalCredit) {}

  record Section(String title, List<Line> lines, BigDecimal total) {}

  record BalanceSheet(
      LocalDate asOf,
      Section assets,
      Section liabilities,
      Section equity,
      BigDecimal currentEarnings,
      BigDecimal liabilitiesAndEquity,
      boolean balanced) {}

  record VatSummary(
      LocalDate from,
      LocalDate to,
      BigDecimal outputVat,
      BigDecimal vatReversed,
      BigDecimal netPayable,
      BigDecimal inputVat,
      /* Output VAT less the VAT given back on returns. */
      BigDecimal netOutputVat,
      /* Input VAT moved to 5150 by the apportionment (STD-2): TRA does not give it back. */
      BigDecimal nonReclaimableInputVat,
      /* Input VAT before the apportionment: inputVat (reclaimable) plus the non-reclaimable. */
      BigDecimal inputVatBeforeApportionment) {}
}
