package tz.co.pharmacy.modules.finance.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tz.co.pharmacy.modules.finance.ports.inbound.ExpenseCategory;
import tz.co.pharmacy.modules.finance.ports.inbound.ReceiptPosting;
import tz.co.pharmacy.modules.finance.ports.inbound.ReturnPosting;
import tz.co.pharmacy.modules.finance.ports.inbound.SalePosting;
import tz.co.pharmacy.platform.kernel.Amounts;

/**
 * The double-entry rules: which accounts each source document debits and credits. Pure functions,
 * so the rules are tested without a database; every result balances or is refused.
 */
final class Postings {

  static final String CASH = "1000";
  static final String BANK = "1010";
  static final String MOBILE_MONEY = "1020";
  static final String RECEIVABLE = "1100";
  static final String INSURANCE_RECEIVABLE = "1110";
  static final String INVENTORY = "1200";
  static final String PAYABLE = "2000";
  static final String TAX_PAYABLE = "2100";
  static final String SALES_REVENUE = "4000";
  static final String SALES_RETURNS = "4100";
  static final String COST_OF_GOODS = "5000";
  static final String STOCK_ADJUSTMENTS = "5100";
  static final String FX_GAINS_LOSSES = "7000";
  static final String VOUCHERS = "2300";
  static final String OWNERS_EQUITY = "3000";

  private Postings() {}

  /**
   * One debit or credit in the base currency; exactly one of the two is more than zero. {@code
   * currency} and {@code foreignAmount} note the foreign amount behind it, or are null.
   */
  record Line(
      String accountCode,
      BigDecimal debit,
      BigDecimal credit,
      String currency,
      BigDecimal foreignAmount) {

    Line(String accountCode, BigDecimal debit, BigDecimal credit) {
      this(accountCode, debit, credit, null, null);
    }

    Line noting(String foreignCurrency, BigDecimal foreign) {
      return foreignCurrency == null || foreign == null
          ? this
          : new Line(accountCode, debit, credit, foreignCurrency, foreign);
    }
  }

  /**
   * Dr the payment accounts (cash less the change given back) / Cr sales revenue (net) and tax
   * payable (tax); Dr cost of goods sold / Cr inventory (cost).
   */
  static List<Line> sale(SalePosting posting) {
    Map<String, BigDecimal> debits = new LinkedHashMap<>();
    posting
        .paymentsByMethod()
        .forEach(
            (method, amount) ->
                debits.merge(paymentAccount(method), money(amount), BigDecimal::add));
    BigDecimal change = money(posting.changeDue());
    if (change.signum() > 0) {
      String from =
          debits.containsKey(CASH) ? CASH : debits.keySet().stream().findFirst().orElse(CASH);
      debits.merge(from, change.negate(), BigDecimal::add);
    }
    List<Line> lines = new ArrayList<>();
    debits.forEach((account, amount) -> debit(lines, account, amount));
    credit(lines, SALES_REVENUE, posting.net());
    credit(lines, TAX_PAYABLE, posting.tax());
    debit(lines, COST_OF_GOODS, posting.cost());
    credit(lines, INVENTORY, posting.cost());
    return balanced(lines);
  }

  /**
   * Dr sales returns (net) and tax payable (tax) / Cr the refund account; Dr inventory / Cr COGS. A
   * refund in a foreign currency credits the refund account with the base value paid out at today's
   * rate, noting the foreign amount; the difference from the booked value is a realised loss (Dr
   * 7000) when positive and a gain (Cr 7000) when negative.
   */
  static List<Line> saleReturn(ReturnPosting posting) {
    BigDecimal booked = money(posting.net()).add(money(posting.tax()));
    BigDecimal difference =
        posting.fxDifference() == null ? Amounts.ZERO : money(posting.fxDifference());
    List<Line> lines = new ArrayList<>();
    debit(lines, SALES_RETURNS, posting.net());
    debit(lines, TAX_PAYABLE, posting.tax());
    List<Line> refund = new ArrayList<>();
    credit(refund, paymentAccount(posting.refundMethod()), booked.add(difference));
    refund.forEach(line -> lines.add(line.noting(posting.currency(), posting.foreignAmount())));
    debit(lines, FX_GAINS_LOSSES, difference);
    debit(lines, INVENTORY, posting.cost());
    credit(lines, COST_OF_GOODS, posting.cost());
    return balanced(lines);
  }

  /**
   * Dr inventory / Cr accounts payable, in the base currency at the receipt's rate; the payable
   * notes the foreign amount owed, which a later supplier payment settles.
   */
  static List<Line> receipt(ReceiptPosting posting) {
    List<Line> lines = new ArrayList<>();
    debit(lines, INVENTORY, posting.total());
    credit(lines, PAYABLE, posting.total());
    if (!lines.isEmpty()) {
      lines.set(1, lines.get(1).noting(posting.currency(), posting.foreignTotal()));
    }
    return balanced(lines);
  }

  /**
   * A supplier payment of {@code foreignAmount}: Dr accounts payable at the booked (receipt) base
   * value / Cr the account it was paid from at the settled base value; the realised exchange
   * difference (settled − booked) is a loss (Dr 7000) when positive and a gain (Cr 7000) when
   * negative.
   */
  static List<Line> supplierPayment(
      BigDecimal bookedBase,
      BigDecimal settledBase,
      String paymentMethod,
      String currency,
      BigDecimal foreignAmount) {
    String noted = currency;
    BigDecimal foreign = foreignAmount;
    List<Line> lines = new ArrayList<>();
    debit(lines, PAYABLE, bookedBase);
    credit(lines, paymentAccount(paymentMethod), settledBase);
    List<Line> notedLines = new ArrayList<>();
    for (Line line : lines) {
      notedLines.add(line.noting(noted, foreign));
    }
    debit(notedLines, FX_GAINS_LOSSES, money(settledBase).subtract(money(bookedBase)));
    return balanced(notedLines);
  }

  /**
   * Opening balances: Dr cash, bank, mobile money, inventory and receivables / Cr payables; owner's
   * equity takes the difference (Cr when positive, Dr when the pharmacy owes more than it holds).
   */
  static List<Line> opening(
      BigDecimal cash,
      BigDecimal bank,
      BigDecimal mobileMoney,
      BigDecimal inventory,
      BigDecimal receivables,
      BigDecimal payables) {
    List<Line> lines = new ArrayList<>();
    debit(lines, CASH, cash);
    debit(lines, BANK, bank);
    debit(lines, MOBILE_MONEY, mobileMoney);
    debit(lines, INVENTORY, inventory);
    debit(lines, RECEIVABLE, receivables);
    credit(lines, PAYABLE, payables);
    BigDecimal equity =
        money(cash)
            .add(money(bank))
            .add(money(mobileMoney))
            .add(money(inventory))
            .add(money(receivables))
            .subtract(money(payables));
    credit(lines, OWNERS_EQUITY, equity);
    return balanced(lines);
  }

  /**
   * A stock adjustment at cost. A loss (negative value): Dr 5100 stock losses / Cr 1200 inventory;
   * a gain (stock found): Dr 1200 inventory / Cr 5100.
   */
  static List<Line> stockAdjustment(BigDecimal value) {
    List<Line> lines = new ArrayList<>();
    BigDecimal amount = value.abs();
    if (value.signum() < 0) {
      debit(lines, STOCK_ADJUSTMENTS, amount);
      credit(lines, INVENTORY, amount);
    } else {
      debit(lines, INVENTORY, amount);
      credit(lines, STOCK_ADJUSTMENTS, amount);
    }
    return balanced(lines);
  }

  /** Stock back to the supplier at cost: Dr accounts payable / Cr inventory. */
  static List<Line> supplierReturn(BigDecimal value) {
    List<Line> lines = new ArrayList<>();
    debit(lines, PAYABLE, value);
    credit(lines, INVENTORY, value);
    return balanced(lines);
  }

  /** A voucher sold: Dr the account the money came into / Cr 2300 Vouchers outstanding. */
  static List<Line> voucherIssued(BigDecimal value, String paidBy) {
    List<Line> lines = new ArrayList<>();
    debit(lines, paymentAccount(paidBy), value);
    credit(lines, VOUCHERS, value);
    return balanced(lines);
  }

  /** Dr the expense account / Cr the account it was paid from. */
  static List<Line> expense(ExpenseCategory category, BigDecimal amount, String paymentMethod) {
    List<Line> lines = new ArrayList<>();
    debit(lines, expenseAccount(category), amount);
    credit(lines, paymentAccount(paymentMethod), amount);
    return balanced(lines);
  }

  /**
   * The account money of a payment method sits in: CASH 1000, CARD or BANK 1010, MOBILE_MONEY 1020,
   * INSURANCE 1110, CREDIT 1100, VOUCHER 2300 (spending prepaid value); anything else is cash.
   */
  static String paymentAccount(String method) {
    if (method == null) {
      return CASH;
    }
    return switch (method.trim().toUpperCase()) {
      case "CARD", "BANK", "BANK_TRANSFER", "CHEQUE" -> BANK;
      case "MOBILE_MONEY" -> MOBILE_MONEY;
      case "INSURANCE" -> INSURANCE_RECEIVABLE;
      case "CREDIT", "ACCOUNT_CREDIT" -> RECEIVABLE;
      case "VOUCHER" -> VOUCHERS;
      default -> CASH;
    };
  }

  static String expenseAccount(ExpenseCategory category) {
    return switch (category) {
      case RENT -> "6000";
      case UTILITIES -> "6010";
      case SALARIES -> "6020";
      case TRANSPORT -> "6030";
      case MAINTENANCE -> "6040";
      case MARKETING -> "6050";
      case SUPPLIES -> "6060";
      case LICENCES -> "6070";
      case OTHER -> "6090";
    };
  }

  /** Refuses an entry whose debits and credits differ: the books would not balance. */
  static List<Line> balanced(List<Line> lines) {
    BigDecimal debits = lines.stream().map(Line::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal credits = lines.stream().map(Line::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
    if (debits.compareTo(credits) != 0) {
      throw new IllegalStateException(
          "Unbalanced journal entry: debits " + debits + " and credits " + credits);
    }
    return List.copyOf(lines);
  }

  private static void debit(List<Line> lines, String account, BigDecimal amount) {
    BigDecimal value = money(amount);
    if (value.signum() > 0) {
      lines.add(new Line(account, value, Amounts.ZERO));
    } else if (value.signum() < 0) {
      lines.add(new Line(account, Amounts.ZERO, value.negate()));
    }
  }

  private static void credit(List<Line> lines, String account, BigDecimal amount) {
    BigDecimal value = money(amount);
    if (value.signum() > 0) {
      lines.add(new Line(account, Amounts.ZERO, value));
    } else if (value.signum() < 0) {
      lines.add(new Line(account, value.negate(), Amounts.ZERO));
    }
  }

  private static BigDecimal money(BigDecimal amount) {
    return Amounts.money(amount);
  }
}
