package tz.co.pharmacy.modules.finance.application;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.finance.ports.inbound.SupplierAccounts;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.kernel.StatementKey;

/**
 * Opening balances and payments per supplier as plain SQL; row-level security keeps them to the
 * current tenant. A payment settles what was booked at the payable's rate, so it counts at {@code
 * booked_base_amount}; any exchange difference went to the FX account, not the supplier.
 */
@Service
@Transactional(readOnly = true)
class SupplierAccountsImpl implements SupplierAccounts {

  private static final String ENTRIES =
      "select l.party_uid as supplier_uid, l.party_name as supplier_name, 'OPENING' as type,"
          + " 0 as ord, l.uid, o.journal_number as number, null as reference,"
          + " o.as_of as on_date, l.amount from finance.opening_balance_lines l"
          + " join finance.opening_balances o on o.id = l.opening_id"
          + " where l.party_type = 'SUPPLIER'"
          + " union all"
          + " select supplier_uid, supplier_name, 'PAYMENT', 2, uid, number, reference, paid_on,"
          + " -booked_base_amount from finance.supplier_payments";

  private static final String KEY = "(on_date, ord, coalesce(number, ''), uid)";

  private final JdbcTemplate jdbc;

  SupplierAccountsImpl(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  public List<Entry> entries(
      UUID supplierUid, LocalDate from, LocalDate to, StatementKey after, int limit) {
    List<Object> args = new ArrayList<>(List.of(supplierUid, Date.valueOf(from), Date.valueOf(to)));
    StringBuilder sql =
        new StringBuilder(
            "select * from ("
                + ENTRIES
                + ") e where supplier_uid = ? and on_date >= ? and on_date <= ?");
    if (after != null) {
      sql.append(" and ").append(KEY).append(" > (?, ?, ?, ?)");
      addKey(args, after);
    }
    sql.append(" order by on_date, ord, coalesce(number, ''), uid limit ?");
    args.add(limit);
    return jdbc.query(
        sql.toString(),
        (rs, i) ->
            new Entry(
                rs.getObject("supplier_uid", UUID.class),
                rs.getString("supplier_name"),
                rs.getString("type"),
                rs.getObject("uid", UUID.class),
                rs.getString("number"),
                rs.getString("reference"),
                rs.getObject("on_date", LocalDate.class),
                rs.getInt("ord"),
                Amounts.money(rs.getBigDecimal("amount"))),
        args.toArray());
  }

  @Override
  public BigDecimal sum(UUID supplierUid, LocalDate from, StatementKey through) {
    List<Object> args = new ArrayList<>(List.of(supplierUid, Date.valueOf(from)));
    String sql =
        "select coalesce(sum(amount), 0) from (" + ENTRIES + ") e where supplier_uid = ? and ";
    if (through == null) {
      sql += "on_date < ?";
    } else {
      sql += "on_date >= ? and " + KEY + " <= (?, ?, ?, ?)";
      addKey(args, through);
    }
    return Amounts.money(jdbc.queryForObject(sql, BigDecimal.class, args.toArray()));
  }

  private static void addKey(List<Object> args, StatementKey key) {
    args.add(Date.valueOf(key.date()));
    args.add(key.order());
    args.add(key.number() == null ? "" : key.number());
    args.add(key.uid());
  }

  @Override
  public List<Totals> totals() {
    return jdbc.query(
        "select supplier_uid, max(supplier_name) as supplier_name,"
            + " coalesce(sum(case when type = 'OPENING' then amount end), 0) as opening,"
            + " coalesce(-sum(case when type = 'PAYMENT' then amount end), 0) as paid,"
            + " max(on_date) as last from ("
            + ENTRIES
            + ") e group by supplier_uid",
        (rs, i) ->
            new Totals(
                rs.getObject("supplier_uid", UUID.class),
                rs.getString("supplier_name"),
                Amounts.money(rs.getBigDecimal("opening")),
                Amounts.money(rs.getBigDecimal("paid")),
                rs.getObject("last", java.time.LocalDate.class)));
  }
}
