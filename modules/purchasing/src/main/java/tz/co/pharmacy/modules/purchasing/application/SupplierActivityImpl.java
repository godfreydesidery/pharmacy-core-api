package tz.co.pharmacy.modules.purchasing.application;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.purchasing.ports.inbound.SupplierActivity;
import tz.co.pharmacy.platform.kernel.Amounts;

/**
 * Receipts and returns per supplier as plain SQL; row-level security keeps them to the current
 * tenant. A receipt counts at its base-currency total, a return at its cost.
 */
@Service
@Transactional(readOnly = true)
class SupplierActivityImpl implements SupplierActivity {

  private static final String ENTRIES =
      "select supplier_uid, supplier_name, 'RECEIPT' as type, uid, number,"
          + " supplier_invoice_number as reference, created_at,"
          + " coalesce(base_total, total) as amount from purchasing.goods_receipts"
          + " union all"
          + " select supplier_uid, supplier_name, 'RETURN', uid, number, goods_receipt_number,"
          + " created_at, -total from purchasing.supplier_returns";

  private final JdbcTemplate jdbc;

  SupplierActivityImpl(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  public List<Entry> entries(UUID supplierUid) {
    return jdbc.query(
        "select * from (" + ENTRIES + ") e where supplier_uid = ? order by created_at, number",
        SupplierActivityImpl::entry,
        supplierUid);
  }

  @Override
  public List<Totals> totals() {
    return jdbc.query(
        "select supplier_uid, max(supplier_name) as supplier_name,"
            + " coalesce(sum(case when type = 'RECEIPT' then amount end), 0) as received,"
            + " coalesce(-sum(case when type = 'RETURN' then amount end), 0) as returned,"
            + " max(created_at) as last from ("
            + ENTRIES
            + ") e group by supplier_uid",
        (rs, i) ->
            new Totals(
                rs.getObject("supplier_uid", UUID.class),
                rs.getString("supplier_name"),
                Amounts.money(rs.getBigDecimal("received")),
                Amounts.money(rs.getBigDecimal("returned")),
                rs.getTimestamp("last").toInstant()));
  }

  private static Entry entry(ResultSet rs, int index) throws SQLException {
    BigDecimal amount = rs.getBigDecimal("amount");
    Instant at = rs.getTimestamp("created_at").toInstant();
    return new Entry(
        rs.getObject("supplier_uid", UUID.class),
        rs.getString("supplier_name"),
        rs.getString("type"),
        rs.getObject("uid", UUID.class),
        rs.getString("number"),
        rs.getString("reference"),
        at,
        Amounts.money(amount));
  }
}
