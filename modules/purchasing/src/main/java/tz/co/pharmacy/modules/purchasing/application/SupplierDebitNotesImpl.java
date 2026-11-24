package tz.co.pharmacy.modules.purchasing.application;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.purchasing.domain.GoodsReceipt;
import tz.co.pharmacy.modules.purchasing.domain.SupplierDebitNote;
import tz.co.pharmacy.modules.purchasing.events.SupplierDebitNoteRecorded;
import tz.co.pharmacy.modules.purchasing.persistence.GoodsReceiptRepository;
import tz.co.pharmacy.modules.purchasing.persistence.SupplierDebitNoteRepository;
import tz.co.pharmacy.modules.purchasing.ports.inbound.SupplierDebitNotes;
import tz.co.pharmacy.modules.purchasing.ports.outbound.PurchasingCurrencies;
import tz.co.pharmacy.modules.purchasing.ports.outbound.PurchasingDirectory;
import tz.co.pharmacy.platform.bus.DomainEventPublisher;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.persistence.DocumentNumbers;

/**
 * Supplier debit notes (STD-2, owner's decision of 24 Nov 2026): a supplier raises what is owed for
 * a goods receipt. Base currency only; numbered PDN-&lt;branch&gt;-000001 per branch; the ledger
 * posts it after commit.
 */
@Service
class SupplierDebitNotesImpl implements SupplierDebitNotes {

  private final GoodsReceiptRepository receipts;
  private final SupplierDebitNoteRepository notes;
  private final PurchasingDirectory directory;
  private final PurchasingCurrencies currencies;
  private final DocumentNumbers numbers;
  private final DomainEventPublisher events;
  private final JdbcTemplate jdbc;

  SupplierDebitNotesImpl(
      GoodsReceiptRepository receipts,
      SupplierDebitNoteRepository notes,
      PurchasingDirectory directory,
      PurchasingCurrencies currencies,
      DocumentNumbers numbers,
      DomainEventPublisher events,
      DataSource dataSource) {
    this.receipts = receipts;
    this.notes = notes;
    this.directory = directory;
    this.currencies = currencies;
    this.numbers = numbers;
    this.events = events;
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  @Transactional
  public SupplierDebitNoteView record(
      UUID goodsReceiptUid, BigDecimal net, BigDecimal vat, String reason, String reference) {
    GoodsReceipt receipt =
        receipts
            .findByUid(goodsReceiptUid)
            .orElseThrow(
                () -> Refusal.notFound("goods_receipt_not_found", "No such goods receipt."));
    String base = currencies.baseCurrency();
    if (receipt.getCurrency() != null && !receipt.getCurrency().equalsIgnoreCase(base)) {
      throw Refusal.invalid(
              "debit_note_base_currency_only",
              "A supplier debit note is kept in "
                  + base
                  + "; this receipt is in "
                  + receipt.getCurrency()
                  + ".",
              "goodsReceiptUid")
          .with("currency", receipt.getCurrency());
    }
    if (net == null || Amounts.money(net).signum() <= 0) {
      throw Refusal.invalid("invalid_amount", "The amount must be more than zero.", "netAmount");
    }
    BigDecimal input =
        vat == null ? Amounts.ZERO : Amounts.money(Checks.notNegative(vat, "vatAmount"));
    if (input.signum() > 0 && !directory.vatRegistered()) {
      throw Refusal.invalid(
          "vat_not_registered",
          "The pharmacy is not VAT-registered: enter the amount with the VAT in it.",
          "vatAmount");
    }
    SupplierDebitNote note =
        notes.saveAndFlush(
            new SupplierDebitNote(
                numbers.format("PDN-" + directory.branchCode(receipt.getBranchUid()), 6),
                receipt,
                Checks.optionalText(reference, "supplierReference", 64),
                Checks.text(reason, "reason", 500),
                Amounts.money(net),
                input));
    events.publish(
        "supplier.debit_note_recorded",
        metadata ->
            new SupplierDebitNoteRecorded(
                metadata,
                note.getUid(),
                note.getNumber(),
                receipt.getUid(),
                note.getBranchUid(),
                note.getSupplierUid(),
                note.getNet(),
                note.getVat(),
                note.getTotal()));
    return view(note);
  }

  @Override
  @Transactional(readOnly = true)
  public List<SupplierDebitNoteView> ofReceipt(UUID goodsReceiptUid) {
    return notes.findByGoodsReceiptUidOrderByIdAsc(goodsReceiptUid).stream()
        .map(SupplierDebitNotesImpl::view)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Map<UUID, BigDecimal> raisedAgainst(java.util.Collection<UUID> receiptUids) {
    Map<UUID, BigDecimal> raised = new LinkedHashMap<>();
    if (receiptUids == null || receiptUids.isEmpty()) {
      return raised;
    }
    // Row-level security keeps this to the current pharmacy.
    jdbc.query(
        "select goods_receipt_uid, sum(total) from purchasing.supplier_debit_notes"
            + " where goods_receipt_uid = any(?) group by goods_receipt_uid",
        ps ->
            ps.setArray(
                1, ps.getConnection().createArrayOf("uuid", receiptUids.toArray(UUID[]::new))),
        rs -> {
          raised.put(rs.getObject(1, UUID.class), rs.getBigDecimal(2));
        });
    return raised;
  }

  @Override
  @Transactional(readOnly = true)
  public SupplierDebitNoteTotals totals(Instant from, Instant to) {
    return jdbc.queryForObject(
        "select coalesce(sum(net), 0) as net, coalesce(sum(vat), 0) as vat"
            + " from purchasing.supplier_debit_notes where created_at >= ? and created_at < ?",
        (rs, i) ->
            new SupplierDebitNoteTotals(
                Amounts.money(rs.getBigDecimal("net")), Amounts.money(rs.getBigDecimal("vat"))),
        Timestamp.from(from),
        Timestamp.from(to));
  }

  /**
   * Receipts and debit notes with input VAT made in [from, to), each with its receipt's lines in
   * the base currency (a line's value at the receipt's rate), for the VAT apportionment.
   */
  @Override
  @Transactional(readOnly = true)
  public List<ReceiptInputVat> inputVat(Instant from, Instant to) {
    Map<UUID, List<ReceiptLineValue>> lines = new LinkedHashMap<>();
    List<Object[]> documents = new ArrayList<>();
    jdbc.query(
        "select d.uid, d.number, d.vat, d.receipt_uid from ("
            + " select uid, number, base_vat_total as vat, uid as receipt_uid, created_at"
            + " from purchasing.goods_receipts where base_vat_total > 0"
            + " union all select uid, number, vat, goods_receipt_uid, created_at"
            + " from purchasing.supplier_debit_notes where vat > 0) d"
            + " where d.created_at >= ? and d.created_at < ? order by d.created_at, d.number",
        row -> {
          documents.add(
              new Object[] {
                row.getObject("uid", UUID.class),
                row.getString("number"),
                row.getBigDecimal("vat"),
                row.getObject("receipt_uid", UUID.class)
              });
        },
        Timestamp.from(from),
        Timestamp.from(to));
    for (Object[] document : documents) {
      UUID receipt = (UUID) document[3];
      lines.computeIfAbsent(
          receipt,
          uid ->
              jdbc.query(
                  "select l.product_uid,"
                      + " sum(round(l.line_total * coalesce(r.exchange_rate, 1), 2)) as value"
                      + " from purchasing.goods_receipt_lines l"
                      + " join purchasing.goods_receipts r on r.id = l.goods_receipt_id"
                      + " where r.uid = ? group by l.product_uid",
                  (rs, i) ->
                      new ReceiptLineValue(
                          rs.getObject("product_uid", UUID.class),
                          Amounts.money(rs.getBigDecimal("value"))),
                  uid));
    }
    return documents.stream()
        .map(
            d ->
                new ReceiptInputVat(
                    (UUID) d[0],
                    (String) d[1],
                    Amounts.money((BigDecimal) d[2]),
                    lines.getOrDefault((UUID) d[3], List.of())))
        .toList();
  }

  private static SupplierDebitNoteView view(SupplierDebitNote note) {
    return new SupplierDebitNoteView(
        note.getUid(),
        note.getNumber(),
        note.getBranchUid(),
        note.getGoodsReceiptUid(),
        note.getGoodsReceiptNumber(),
        note.getSupplierUid(),
        note.getSupplierName(),
        note.getSupplierReference(),
        note.getReason(),
        note.getNet(),
        note.getVat(),
        note.getTotal(),
        note.getCreatedAt());
  }
}
