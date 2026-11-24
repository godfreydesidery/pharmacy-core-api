package tz.co.pharmacy.modules.purchasing.application;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.purchasing.ports.inbound.SupplierActivity;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.kernel.StatementKey;

/**
 * Receipts, the supplier's debit notes and credited returns per supplier as plain SQL; row-level
 * security keeps them to the current tenant. A receipt counts at its base-currency total; a return
 * at its credit note's base value (at its total for returns from before credit notes), on the day
 * the credit was recorded. A return awaiting its credit note counts only in {@code awaitingCredit}.
 */
@Service
@Transactional(readOnly = true)
class SupplierActivityImpl implements SupplierActivity {

  /** The entries with their local day; the first parameter is the time zone. */
  private static final String ENTRIES =
      "select supplier_uid, supplier_name, 'RECEIPT' as type, 1 as ord, uid, number,"
          + " supplier_invoice_number as reference, (created_at at time zone ?)::date as on_date,"
          + " coalesce(base_total, total) as amount from purchasing.goods_receipts"
          + " where supplier_uid = ?"
          + " union all"
          + " select supplier_uid, supplier_name, 'RETURN', 3, uid, number,"
          + " coalesce(credit_note_number, goods_receipt_number),"
          + " (coalesce(credited_at, created_at) at time zone ?)::date,"
          + " -coalesce(credit_base_amount, total) from purchasing.supplier_returns"
          + " where supplier_uid = ? and status = 'CREDITED'"
          // A supplier's debit note (STD-2) raises what is owed, like a receipt.
          + " union all"
          + " select supplier_uid, supplier_name, 'DEBIT_NOTE', 1, uid, number,"
          + " coalesce(supplier_reference, goods_receipt_number),"
          + " (created_at at time zone ?)::date, total from purchasing.supplier_debit_notes"
          + " where supplier_uid = ?";

  private static final String AFTER =
      " and (on_date, ord, coalesce(number, ''), uid) > (?, ?, ?, ?)";
  private static final String THROUGH =
      " and (on_date, ord, coalesce(number, ''), uid) <= (?, ?, ?, ?)";

  private final JdbcTemplate jdbc;

  SupplierActivityImpl(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  public List<Entry> entries(
      UUID supplierUid, LocalDate from, LocalDate to, ZoneId zone, StatementKey after, int limit) {
    List<Object> args = new ArrayList<>(entriesArgs(supplierUid, zone));
    StringBuilder sql =
        new StringBuilder("select * from (" + ENTRIES + ") e where on_date >= ? and on_date <= ?");
    args.add(Date.valueOf(from));
    args.add(Date.valueOf(to));
    if (after != null) {
      sql.append(AFTER);
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
  public BigDecimal sum(UUID supplierUid, LocalDate from, StatementKey through, ZoneId zone) {
    List<Object> args = new ArrayList<>(entriesArgs(supplierUid, zone));
    StringBuilder sql =
        new StringBuilder("select coalesce(sum(amount), 0) from (" + ENTRIES + ") e where ");
    if (through == null) {
      sql.append("on_date < ?");
      args.add(Date.valueOf(from));
    } else {
      sql.append("on_date >= ?").append(THROUGH);
      args.add(Date.valueOf(from));
      addKey(args, through);
    }
    return Amounts.money(jdbc.queryForObject(sql.toString(), BigDecimal.class, args.toArray()));
  }

  private static List<Object> entriesArgs(UUID supplierUid, ZoneId zone) {
    return List.of(zone.getId(), supplierUid, zone.getId(), supplierUid, zone.getId(), supplierUid);
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
            + " coalesce(sum(received), 0) as received, coalesce(sum(returned), 0) as returned,"
            + " coalesce(sum(awaiting), 0) as awaiting, max(at) as last from ("
            + " select supplier_uid, supplier_name, coalesce(base_total, total) as received,"
            + " 0 as returned, 0 as awaiting, created_at as at from purchasing.goods_receipts"
            + " union all"
            + " select supplier_uid, supplier_name, 0,"
            + " case when status = 'CREDITED' then coalesce(credit_base_amount, total) else 0 end,"
            + " case when status = 'PENDING_CREDIT' then total else 0 end,"
            + " coalesce(credited_at, created_at) from purchasing.supplier_returns"
            + " union all"
            + " select supplier_uid, supplier_name, total, 0, 0, created_at"
            + " from purchasing.supplier_debit_notes"
            + ") e group by supplier_uid",
        (rs, i) ->
            new Totals(
                rs.getObject("supplier_uid", UUID.class),
                rs.getString("supplier_name"),
                Amounts.money(rs.getBigDecimal("received")),
                Amounts.money(rs.getBigDecimal("returned")),
                Amounts.money(rs.getBigDecimal("awaiting")),
                rs.getTimestamp("last").toInstant()));
  }
}
