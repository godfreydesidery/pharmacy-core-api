package tz.co.pharmacy.modules.sales.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.sales.domain.Sale;
import tz.co.pharmacy.modules.sales.domain.SaleDebitNote;
import tz.co.pharmacy.modules.sales.events.SaleDebitNoteIssued;
import tz.co.pharmacy.modules.sales.persistence.SaleDebitNoteRepository;
import tz.co.pharmacy.modules.sales.persistence.SaleRepository;
import tz.co.pharmacy.modules.sales.ports.inbound.DebitNoteView;
import tz.co.pharmacy.modules.sales.ports.inbound.SaleDebitNotes;
import tz.co.pharmacy.modules.sales.ports.outbound.SalesSettings;
import tz.co.pharmacy.platform.bus.DomainEventPublisher;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.kernel.TaxCategory;
import tz.co.pharmacy.platform.persistence.DocumentNumbers;

/**
 * Sales debit notes (STD-2, owner's decision of 24 Nov 2026): a customer charged more after the
 * invoice. Numbered SDN-&lt;branch&gt;-000001 per branch, VAT at the standard rate for a STANDARD
 * supply while the pharmacy is VAT-registered (0 otherwise), a fiscal DEBIT_NOTE document when the
 * sale had a fiscal receipt. The ledger posts it after commit; the caller charges the account.
 */
@Service
class SaleDebitNotesImpl implements SaleDebitNotes {

  private final SaleRepository sales;
  private final SaleDebitNoteRepository notes;
  private final DocumentNumbers numbers;
  private final SalesSettings settings;
  private final DomainEventPublisher events;
  private final FiscalDocumentsImpl fiscal;

  SaleDebitNotesImpl(
      SaleRepository sales,
      SaleDebitNoteRepository notes,
      DocumentNumbers numbers,
      SalesSettings settings,
      DomainEventPublisher events,
      FiscalDocumentsImpl fiscal) {
    this.sales = sales;
    this.notes = notes;
    this.numbers = numbers;
    this.settings = settings;
    this.events = events;
    this.fiscal = fiscal;
  }

  @Override
  @Transactional
  public DebitNoteView issue(UUID saleUid, BigDecimal net, String taxCategory, String reason) {
    Sale sale =
        sales
            .findByUid(saleUid)
            .orElseThrow(() -> Refusal.notFound("sale_not_found", "No such sale."));
    if (sale.getCustomerUid() == null) {
      throw Refusal.invalid(
          "debit_note_needs_customer",
          "A debit note is charged to the sale's customer; this sale has none.",
          "saleUid");
    }
    if (net == null || Amounts.money(net).signum() <= 0) {
      throw Refusal.invalid("invalid_amount", "The amount must be more than zero.", "netAmount");
    }
    String why = Checks.text(reason, "reason", 500);
    TaxCategory category =
        taxCategory == null || taxCategory.isBlank()
            ? TaxCategory.STANDARD
            : Checks.oneOf(TaxCategory.class, taxCategory, "taxCategory");
    BigDecimal rate =
        category == TaxCategory.STANDARD && settings.seller().vatRegistered()
            ? settings.standardVatRatePercent()
            : BigDecimal.ZERO;
    BigDecimal amount = Amounts.money(net);
    BigDecimal tax =
        Amounts.money(
            amount.multiply(rate).divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP));
    SaleDebitNote note =
        notes.saveAndFlush(
            new SaleDebitNote(
                numbers.format("SDN-" + settings.branchCode(sale.getBranchUid()), 6),
                sale,
                why,
                category,
                rate.setScale(2, RoundingMode.HALF_UP),
                amount,
                tax));
    if (fiscal.creditNoteFor(sale)) {
      fiscal.newDebitNote(note, sale);
    }
    events.publish(
        "sale.debit_note_issued",
        metadata ->
            new SaleDebitNoteIssued(
                metadata,
                note.getUid(),
                note.getNumber(),
                sale.getUid(),
                sale.getNumber(),
                note.getBranchUid(),
                note.getCustomerUid(),
                category.name(),
                note.getNet(),
                note.getTax(),
                note.getTotal()));
    return view(note);
  }

  @Override
  @Transactional(readOnly = true)
  public List<DebitNoteView> ofSale(UUID saleUid) {
    return notes.findBySaleUidOrderByIdAsc(saleUid).stream().map(SaleDebitNotesImpl::view).toList();
  }

  @Override
  public void fiscalise(UUID debitNoteUid) {
    fiscal.fiscaliseDebitNote(debitNoteUid);
  }

  private static DebitNoteView view(SaleDebitNote note) {
    return new DebitNoteView(
        note.getUid(),
        note.getNumber(),
        note.getBranchUid(),
        note.getSaleUid(),
        note.getSaleNumber(),
        note.getCustomerUid(),
        note.getReason(),
        note.getTaxCategory().name(),
        note.getTaxRatePercent(),
        note.getNet(),
        note.getTax(),
        note.getTotal(),
        note.getFiscalStatus() == null ? null : note.getFiscalStatus().name(),
        note.getFiscalReference(),
        note.getCreatedAt());
  }
}
