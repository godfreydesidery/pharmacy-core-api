package tz.co.pharmacy.modules.finance.application;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.finance.ports.inbound.AccountType;
import tz.co.pharmacy.platform.kernel.Clocks;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Uids;

/**
 * The default chart of accounts every tenant starts with. It is seeded on first use in the current
 * tenant; seeding again inserts nothing ({@code on conflict do nothing}).
 */
@Component
class ChartOfAccounts {

  record Seed(String code, String name, AccountType type) {}

  static final List<Seed> DEFAULT =
      List.of(
          new Seed("1000", "Cash", AccountType.ASSET),
          new Seed("1010", "Bank", AccountType.ASSET),
          new Seed("1020", "Mobile Money", AccountType.ASSET),
          new Seed("1100", "Accounts Receivable", AccountType.ASSET),
          new Seed("1110", "Insurance Receivable", AccountType.ASSET),
          // STD-2: input VAT paid to suppliers, recoverable from TRA against output VAT (2100).
          new Seed("1150", "VAT Receivable", AccountType.ASSET),
          new Seed("1200", "Inventory", AccountType.ASSET),
          // STD-3: the expiry write-down, a contra-asset (credit balance) beside 1200.
          new Seed("1210", "Inventory Expiry Provision", AccountType.ASSET),
          new Seed("2000", "Accounts Payable", AccountType.LIABILITY),
          new Seed("2100", "Tax Payable", AccountType.LIABILITY),
          new Seed("2300", "Vouchers Outstanding", AccountType.LIABILITY),
          new Seed("3000", "Owner's Equity", AccountType.EQUITY),
          new Seed("4000", "Sales Revenue", AccountType.REVENUE),
          new Seed("4100", "Sales Returns", AccountType.REVENUE),
          new Seed("5000", "Cost of Goods Sold", AccountType.EXPENSE),
          new Seed("5100", "Stock Losses & Adjustments", AccountType.EXPENSE),
          new Seed("5110", "Expiry Write-down", AccountType.EXPENSE),
          // STD-2: a supplier's debit note on goods already received (their cost is not changed).
          new Seed("5120", "Purchase Price Differences", AccountType.EXPENSE),
          // STD-2: input VAT that exempt sales use, which TRA does not give back.
          new Seed("5150", "Non-reclaimable Input VAT", AccountType.EXPENSE),
          new Seed("6000", "Rent", AccountType.EXPENSE),
          new Seed("6010", "Utilities", AccountType.EXPENSE),
          new Seed("6020", "Salaries", AccountType.EXPENSE),
          new Seed("6030", "Transport", AccountType.EXPENSE),
          new Seed("6040", "Maintenance", AccountType.EXPENSE),
          new Seed("6050", "Marketing", AccountType.EXPENSE),
          new Seed("6060", "Supplies", AccountType.EXPENSE),
          new Seed("6070", "Licences", AccountType.EXPENSE),
          new Seed("6090", "Other Expenses", AccountType.EXPENSE),
          new Seed("6095", "Insurance Rejections", AccountType.EXPENSE),
          new Seed("6096", "Bad Debts", AccountType.EXPENSE),
          new Seed("7000", "FX Gains/Losses", AccountType.EXPENSE));

  private final JdbcTemplate jdbc;

  ChartOfAccounts(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  /** The default chart's codes, as a SQL array literal. */
  private static final String DEFAULT_CODES =
      "{" + String.join(",", DEFAULT.stream().map(Seed::code).toList()) + "}";

  /**
   * Seeds the default chart in the current tenant unless every default account is there: a chart
   * from before an account was added to the defaults (1150 VAT Receivable) gets it on first use.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  void ensure() {
    Integer present =
        jdbc.queryForObject(
            "select count(*) from finance.accounts where code = any(cast(? as text[]))",
            Integer.class,
            DEFAULT_CODES);
    if (present != null && present >= DEFAULT.size()) {
      return;
    }
    String actor = ExecutionContext.actor().reference();
    java.util.UUID actorUid = ExecutionContext.actor().uid();
    for (Seed seed : DEFAULT) {
      jdbc.update(
          "insert into finance.accounts (uid, tenant_uid, code, name, type, created_at, created_by,"
              + " created_by_uid, version)"
              + " values (?, platform.current_tenant_uid(), ?, ?, ?, ?, ?, ?, 0)"
              + " on conflict (tenant_uid, code) do nothing",
          Uids.newUid(),
          seed.code(),
          seed.name(),
          seed.type().name(),
          Timestamp.from(Clocks.now()),
          actor,
          actorUid);
    }
  }

  /** Account names by code, in the current tenant. */
  @Transactional(propagation = Propagation.MANDATORY)
  Map<String, String> names() {
    Map<String, String> names = new LinkedHashMap<>();
    jdbc.query(
        "select code, name from finance.accounts order by code",
        row -> {
          names.put(row.getString("code"), row.getString("name"));
        });
    return names;
  }
}
