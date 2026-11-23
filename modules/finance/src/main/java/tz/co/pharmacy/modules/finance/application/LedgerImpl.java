package tz.co.pharmacy.modules.finance.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.finance.domain.JournalEntry;
import tz.co.pharmacy.modules.finance.domain.OpeningBalance;
import tz.co.pharmacy.modules.finance.persistence.JournalEntryRepository;
import tz.co.pharmacy.modules.finance.persistence.OpeningBalanceRepository;
import tz.co.pharmacy.modules.finance.ports.inbound.AccountType;
import tz.co.pharmacy.modules.finance.ports.inbound.AccountView;
import tz.co.pharmacy.modules.finance.ports.inbound.JournalEntryView;
import tz.co.pharmacy.modules.finance.ports.inbound.JournalLineView;
import tz.co.pharmacy.modules.finance.ports.inbound.Ledger;
import tz.co.pharmacy.modules.finance.ports.inbound.OpeningBalanceView;
import tz.co.pharmacy.modules.finance.ports.inbound.OpeningBalancesCommand;
import tz.co.pharmacy.modules.finance.ports.inbound.ReceiptPosting;
import tz.co.pharmacy.modules.finance.ports.inbound.ReturnPosting;
import tz.co.pharmacy.modules.finance.ports.inbound.SalePosting;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.kernel.TenantCalendar;
import tz.co.pharmacy.platform.persistence.Keyset;
import tz.co.pharmacy.platform.persistence.Specs;
import tz.co.pharmacy.platform.persistence.TenantContext;

@Service
class LedgerImpl implements Ledger {

  static final String SALE = "SALE";
  static final String SALE_RETURN = "SALE_RETURN";
  static final String GOODS_RECEIPT = "GOODS_RECEIPT";
  static final String EXPENSE = "EXPENSE";
  static final String OPENING_BALANCE = "OPENING_BALANCE";
  static final String VOUCHER = "VOUCHER";
  static final String SUPPLIER_RETURN = "SUPPLIER_RETURN";
  static final String STOCK_ADJUSTMENT = "STOCK_ADJUSTMENT";

  private final JournalWriter writer;
  private final JournalEntryRepository entries;
  private final ChartOfAccounts chart;
  private final JdbcTemplate jdbc;
  private final OpeningBalanceRepository openings;
  private final TenantCalendar calendar;

  LedgerImpl(
      JournalWriter writer,
      JournalEntryRepository entries,
      ChartOfAccounts chart,
      DataSource dataSource,
      OpeningBalanceRepository openings,
      TenantCalendar calendar) {
    this.calendar = calendar;
    this.writer = writer;
    this.entries = entries;
    this.chart = chart;
    this.jdbc = new JdbcTemplate(dataSource);
    this.openings = openings;
  }

  /**
   * Balances on each account's normal side: debit less credit for assets and expenses, credit less
   * debit for liabilities, equity and revenue. Read from the daily balances (PERF-4), which every
   * journal line updates in its own transaction: a few rows per account and day, not every line.
   */
  @Override
  @Transactional
  public List<AccountView> accounts() {
    chart.ensure();
    List<AccountView> accounts = new ArrayList<>();
    jdbc.query(
        "select a.code, a.name, a.type, coalesce(sum(b.debit), 0) as debit,"
            + " coalesce(sum(b.credit), 0) as credit"
            + " from finance.accounts a"
            + " left join finance.account_daily_balances b"
            + "   on b.tenant_uid = a.tenant_uid and b.account_code = a.code"
            + " group by a.code, a.name, a.type order by a.code",
        row -> {
          AccountType type = AccountType.valueOf(row.getString("type"));
          BigDecimal debit = row.getBigDecimal("debit");
          BigDecimal credit = row.getBigDecimal("credit");
          BigDecimal balance =
              type == AccountType.ASSET || type == AccountType.EXPENSE
                  ? debit.subtract(credit)
                  : credit.subtract(debit);
          accounts.add(
              new AccountView(
                  row.getString("code"), row.getString("name"), type, Amounts.money(balance)));
        });
    return accounts;
  }

  @Override
  @Transactional(readOnly = true)
  public Page<JournalEntryView> journal(LocalDate from, LocalDate to, PageQuery query) {
    Specification<JournalEntry> filter =
        Specs.<JournalEntry, LocalDate>atLeast("entryDate", from)
            .and(Specs.before("entryDate", to == null ? null : to.plusDays(1)));
    return Keyset.page(entries, filter, query, LedgerImpl::toView);
  }

  @Override
  @Transactional
  public void postSale(SalePosting posting) {
    writer.post(
        SALE,
        posting.saleUid(),
        posting.number(),
        "Sale " + posting.number(),
        posting.branchUid(),
        posting.date(),
        Postings.sale(posting));
  }

  @Override
  @Transactional
  public void postReturn(ReturnPosting posting) {
    writer.post(
        SALE_RETURN,
        posting.returnUid(),
        posting.number(),
        "Sale return " + posting.number(),
        posting.branchUid(),
        posting.date(),
        Postings.saleReturn(posting));
  }

  @Override
  @Transactional
  public void postReceipt(ReceiptPosting posting) {
    writer.post(
        GOODS_RECEIPT,
        posting.receiptUid(),
        posting.number(),
        "Goods receipt " + posting.number(),
        posting.branchUid(),
        posting.date(),
        Postings.receipt(posting));
  }

  @Override
  @Transactional
  public void postSupplierReturn(
      java.util.UUID returnUid,
      String number,
      java.util.UUID branchUid,
      java.time.LocalDate date,
      java.math.BigDecimal value) {
    writer.post(
        SUPPLIER_RETURN,
        returnUid,
        number,
        "Return to supplier " + number,
        branchUid,
        date,
        Postings.supplierReturn(value));
  }

  @Override
  @Transactional
  public void postStockAdjustment(
      java.util.UUID movementUid,
      String reference,
      java.util.UUID branchUid,
      java.time.LocalDate date,
      java.math.BigDecimal value) {
    if (value == null || value.signum() == 0) {
      return;
    }
    writer.post(
        STOCK_ADJUSTMENT,
        movementUid,
        "ADJ-" + movementUid.toString().substring(0, 8).toUpperCase(),
        "Stock adjustment: " + reference,
        branchUid,
        date,
        Postings.stockAdjustment(value));
  }

  @Override
  @Transactional
  public void postVoucherIssued(
      java.util.UUID voucherUid,
      String code,
      java.util.UUID branchUid,
      java.time.LocalDate date,
      java.math.BigDecimal value,
      String paidBy) {
    writer.post(
        VOUCHER,
        voucherUid,
        code,
        "Voucher " + code + " sold",
        branchUid,
        date,
        Postings.voucherIssued(value, paidBy));
  }

  @Override
  @Transactional(readOnly = true)
  public boolean hasEntries() {
    return entries.count() > 0;
  }

  @Override
  @Transactional
  public OpeningBalanceView postOpening(OpeningBalancesCommand command) {
    Checks.required(command, "openingBalances");
    UUID tenant = TenantContext.requireTenant();
    if (openings.findFirstByOrderByIdAsc().isPresent()
        || entries.existsBySourceTypeAndSourceUid(OPENING_BALANCE, tenant)) {
      throw Refusal.conflict(
          "opening_balances_posted", "The opening balances were already posted.");
    }
    LocalDate asOf = command.asOf() == null ? calendar.today() : command.asOf();
    OpeningBalance opening =
        new OpeningBalance(
            asOf,
            notNegative(command.cash(), "cash"),
            notNegative(command.bank(), "bank"),
            notNegative(command.mobileMoney(), "mobileMoney"),
            notNegative(command.inventory(), "inventory"));
    parties(opening, "CUSTOMER", command.receivables(), "receivables");
    parties(opening, "SUPPLIER", command.payables(), "payables");
    writer.post(
        OPENING_BALANCE,
        tenant,
        "OPENING",
        "Opening balances as of " + asOf,
        null,
        asOf,
        Postings.opening(
            opening.getCash(),
            opening.getBank(),
            opening.getMobileMoney(),
            opening.getInventory(),
            opening.getReceivables(),
            opening.getPayables()));
    String number =
        entries
            .findBySourceTypeAndSourceUid(OPENING_BALANCE, tenant)
            .map(JournalEntry::getNumber)
            .orElse(null);
    opening.close(number);
    return openingView(openings.save(opening));
  }

  @Override
  @Transactional(readOnly = true)
  public java.util.Optional<OpeningBalanceView> opening() {
    return openings.findFirstByOrderByIdAsc().map(LedgerImpl::openingView);
  }

  @Override
  @Transactional
  public BigDecimal balance(String accountCode) {
    return accounts().stream()
        .filter(account -> account.code().equals(accountCode))
        .map(AccountView::balance)
        .findFirst()
        .orElse(Amounts.ZERO);
  }

  private static BigDecimal notNegative(BigDecimal value, String field) {
    return value == null ? Amounts.ZERO : Checks.notNegative(value, field);
  }

  private static void parties(
      OpeningBalance opening,
      String type,
      List<OpeningBalancesCommand.Party> parties,
      String field) {
    if (parties == null) {
      return;
    }
    java.util.Set<UUID> seen = new java.util.HashSet<>();
    for (int i = 0; i < parties.size(); i++) {
      OpeningBalancesCommand.Party party = parties.get(i);
      String at = field + "[" + i + "]";
      UUID uid = Checks.required(party.uid(), at + ".uid");
      if (!seen.add(uid)) {
        throw Refusal.invalid("duplicate_party", "Each party appears once.", at + ".uid");
      }
      BigDecimal amount = Checks.required(party.amount(), at + ".amount");
      if (amount.signum() <= 0) {
        throw Refusal.invalid(
            "invalid_amount", "An amount must be more than zero.", at + ".amount");
      }
      opening.addParty(type, uid, Checks.text(party.name(), at + ".name", 200), amount);
    }
  }

  private static OpeningBalanceView openingView(OpeningBalance o) {
    return new OpeningBalanceView(
        o.getUid(),
        o.getAsOf(),
        o.getCash(),
        o.getBank(),
        o.getMobileMoney(),
        o.getInventory(),
        o.getReceivables(),
        o.getPayables(),
        o.getCapital(),
        o.getJournalNumber(),
        o.getLines().stream()
            .map(
                line ->
                    new OpeningBalanceView.PartyLine(
                        line.getPartyType(),
                        line.getPartyUid(),
                        line.getPartyName(),
                        line.getAmount()))
            .toList(),
        o.getCreatedAt(),
        o.getCreatedBy());
  }

  static JournalEntryView toView(JournalEntry entry) {
    return new JournalEntryView(
        entry.getUid(),
        entry.getNumber(),
        entry.getEntryDate(),
        entry.getSourceType(),
        entry.getSourceReference(),
        entry.getDescription(),
        entry.getBranchUid(),
        entry.getLines().stream()
            .map(
                line ->
                    new JournalLineView(
                        line.getAccountCode(),
                        line.getAccountName(),
                        line.getDebit(),
                        line.getCredit(),
                        line.getCurrency(),
                        line.getForeignAmount()))
            .toList(),
        entry.getCreatedAt());
  }
}
