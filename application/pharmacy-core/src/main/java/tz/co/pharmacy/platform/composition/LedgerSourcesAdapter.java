package tz.co.pharmacy.platform.composition;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.finance.ports.inbound.UnpostedDocument;
import tz.co.pharmacy.modules.finance.ports.outbound.LedgerSources;

/**
 * The documents the ledger posts after commit, read straight from each module's table (as the
 * tenant export does): one cheap range query, under the caller's transaction and the tenant's
 * row-level security. Each row carries the journal source type its posting uses (see
 * LedgerReactions and CreditReactions); documents worth nothing post no entry and are left out.
 */
@Component
class LedgerSourcesAdapter implements LedgerSources {

  private static final String SOURCES =
      """
      select 'SALE', uid, number, branch_uid, created_at from sales.sales
       where created_at >= ? and created_at < ? and total <> 0
      union all
      select 'SALE_RETURN', uid, number, branch_uid, created_at from sales.sale_returns
       where created_at >= ? and created_at < ? and refund_total <> 0
      union all
      select 'SALE_RETURN', l.uid, r.number, r.branch_uid, l.released_at
        from sales.sale_return_lines l join sales.sale_returns r on r.id = l.return_id
       where l.released_at >= ? and l.released_at < ? and l.cost <> 0
      union all
      select 'VOUCHER', uid, code, branch_uid, created_at from sales.vouchers
       where created_at >= ? and created_at < ?
      union all
      select 'GOODS_RECEIPT', uid, number, branch_uid, created_at from purchasing.goods_receipts
       where created_at >= ? and created_at < ? and total <> 0
      union all
      select 'SUPPLIER_RETURN', uid, number, branch_uid, created_at from purchasing.supplier_returns
       where created_at >= ? and created_at < ? and total <> 0
      union all
      select 'CUSTOMER_PAYMENT', uid, reference, null, created_at from receivables.entries
       where created_at >= ? and created_at < ? and type = 'PAYMENT'
      union all
      select 'INSURANCE_REMITTANCE', uid, number, null, created_at from insurance.remittances
       where created_at >= ? and created_at < ?
      union all
      select 'STOCK_ADJUSTMENT', m.uid, m.product_name, m.branch_uid, m.created_at
        from inventory.movements m join inventory.batches b on b.uid = m.batch_uid
       where m.created_at >= ? and m.created_at < ? and m.type = 'ADJUSTMENT'
         and m.quantity_change <> 0 and b.unit_cost > 0
      union all
      select 'SALE_DEBIT_NOTE', uid, number, branch_uid, created_at from sales.debit_notes
       where created_at >= ? and created_at < ?
      union all
      select 'SUPPLIER_DEBIT_NOTE', uid, number, branch_uid, created_at
        from purchasing.supplier_debit_notes
       where created_at >= ? and created_at < ?
      """;

  private static final int RANGES = 11;

  private final JdbcTemplate jdbc;

  LedgerSourcesAdapter(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  public List<UnpostedDocument> recorded(Instant from, Instant before) {
    Object[] args = new Object[RANGES * 2];
    for (int i = 0; i < RANGES; i++) {
      args[2 * i] = Timestamp.from(from);
      args[2 * i + 1] = Timestamp.from(before);
    }
    List<UnpostedDocument> documents = new ArrayList<>();
    jdbc.query(
        SOURCES,
        row -> {
          documents.add(
              new UnpostedDocument(
                  row.getString(1),
                  row.getObject(2, UUID.class),
                  row.getString(3),
                  row.getObject(4, UUID.class),
                  row.getTimestamp(5).toInstant()));
        },
        args);
    return documents;
  }
}
