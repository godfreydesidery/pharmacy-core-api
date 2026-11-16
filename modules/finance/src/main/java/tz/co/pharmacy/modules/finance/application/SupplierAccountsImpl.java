package tz.co.pharmacy.modules.finance.application;

import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.finance.ports.inbound.SupplierAccounts;
import tz.co.pharmacy.platform.kernel.Amounts;

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
          + " l.uid, o.journal_number as number, null as reference, o.as_of as on_date,"
          + " l.amount from finance.opening_balance_lines l"
          + " join finance.opening_balances o on o.id = l.opening_id"
          + " where l.party_type = 'SUPPLIER'"
          + " union all"
          + " select supplier_uid, supplier_name, 'PAYMENT', uid, number, reference, paid_on,"
          + " -booked_base_amount from finance.supplier_payments";

  private final JdbcTemplate jdbc;

  SupplierAccountsImpl(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  public List<Entry> entries(UUID supplierUid) {
    return jdbc.query(
        "select * from (" + ENTRIES + ") e where supplier_uid = ? order by on_date, number",
        (rs, i) ->
            new Entry(
                rs.getObject("supplier_uid", UUID.class),
                rs.getString("supplier_name"),
                rs.getString("type"),
                rs.getObject("uid", UUID.class),
                rs.getString("number"),
                rs.getString("reference"),
                rs.getObject("on_date", java.time.LocalDate.class),
                Amounts.money(rs.getBigDecimal("amount"))),
        supplierUid);
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
