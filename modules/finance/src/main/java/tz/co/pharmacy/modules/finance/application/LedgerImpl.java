package tz.co.pharmacy.modules.finance.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.finance.domain.JournalEntry;
import tz.co.pharmacy.modules.finance.persistence.JournalEntryRepository;
import tz.co.pharmacy.modules.finance.ports.inbound.AccountType;
import tz.co.pharmacy.modules.finance.ports.inbound.AccountView;
import tz.co.pharmacy.modules.finance.ports.inbound.JournalEntryView;
import tz.co.pharmacy.modules.finance.ports.inbound.JournalLineView;
import tz.co.pharmacy.modules.finance.ports.inbound.Ledger;
import tz.co.pharmacy.modules.finance.ports.inbound.ReceiptPosting;
import tz.co.pharmacy.modules.finance.ports.inbound.ReturnPosting;
import tz.co.pharmacy.modules.finance.ports.inbound.SalePosting;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;
import tz.co.pharmacy.platform.persistence.Keyset;
import tz.co.pharmacy.platform.persistence.Specs;

@Service
class LedgerImpl implements Ledger {

  static final String SALE = "SALE";
  static final String SALE_RETURN = "SALE_RETURN";
  static final String GOODS_RECEIPT = "GOODS_RECEIPT";
  static final String EXPENSE = "EXPENSE";

  private final JournalWriter writer;
  private final JournalEntryRepository entries;
  private final ChartOfAccounts chart;
  private final JdbcTemplate jdbc;

  LedgerImpl(
      JournalWriter writer,
      JournalEntryRepository entries,
      ChartOfAccounts chart,
      DataSource dataSource) {
    this.writer = writer;
    this.entries = entries;
    this.chart = chart;
    this.jdbc = new JdbcTemplate(dataSource);
  }

  /**
   * Balances on each account's normal side: debit less credit for assets and expenses, credit less
   * debit for liabilities, equity and revenue.
   */
  @Override
  @Transactional
  public List<AccountView> accounts() {
    chart.ensure();
    List<AccountView> accounts = new ArrayList<>();
    jdbc.query(
        "select a.code, a.name, a.type, coalesce(sum(l.debit), 0) as debit,"
            + " coalesce(sum(l.credit), 0) as credit"
            + " from finance.accounts a"
            + " left join finance.journal_lines l on l.account_code = a.code"
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
