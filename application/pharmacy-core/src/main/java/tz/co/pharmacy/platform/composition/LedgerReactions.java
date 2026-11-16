package tz.co.pharmacy.platform.composition;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import tz.co.pharmacy.modules.finance.ports.inbound.Ledger;
import tz.co.pharmacy.modules.finance.ports.inbound.ReceiptPosting;
import tz.co.pharmacy.modules.finance.ports.inbound.ReturnPosting;
import tz.co.pharmacy.modules.finance.ports.inbound.SalePosting;
import tz.co.pharmacy.modules.inventory.events.StockAdjusted;
import tz.co.pharmacy.modules.purchasing.events.GoodsReceived;
import tz.co.pharmacy.modules.purchasing.events.SupplierReturned;
import tz.co.pharmacy.modules.sales.events.SaleCompleted;
import tz.co.pharmacy.modules.sales.events.SaleReturned;
import tz.co.pharmacy.modules.sales.events.VoucherIssued;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantService;
import tz.co.pharmacy.platform.bus.EventScopes;

/**
 * Posts the journal entries of sales, sale returns and goods receipts after they commit, on the
 * local date of the tenant. Posting is idempotent per source document; a duplicate that slips past
 * the check is refused by the unique constraint and logged, so the event still counts as handled.
 */
@Component
public class LedgerReactions {

  private static final Logger LOG = LoggerFactory.getLogger(LedgerReactions.class);
  private static final String SERVICE = "ledger-posting";

  private final Ledger ledger;
  private final TenantService tenants;

  public LedgerReactions(Ledger ledger, TenantService tenants) {
    this.ledger = ledger;
    this.tenants = tenants;
  }

  @Async
  @TransactionalEventListener
  public void on(SaleCompleted event) {
    EventScopes.run(
        event.metadata(),
        SERVICE,
        () ->
            once(
                () ->
                    ledger.postSale(
                        new SalePosting(
                            event.saleUid(),
                            event.number(),
                            event.branchUid(),
                            localDate(event.metadata().tenantUid(), event.metadata().occurredAt()),
                            event.total().subtract(event.taxTotal()),
                            event.taxTotal(),
                            cost(event.lines()),
                            event.changeDue(),
                            paymentsByMethod(event.payments())))));
  }

  @Async
  @TransactionalEventListener
  public void on(VoucherIssued event) {
    EventScopes.run(
        event.metadata(),
        SERVICE,
        () ->
            once(
                () ->
                    ledger.postVoucherIssued(
                        event.voucherUid(),
                        event.code(),
                        event.branchUid(),
                        localDate(event.metadata().tenantUid(), event.metadata().occurredAt()),
                        event.value(),
                        event.paidBy())));
  }

  @Async
  @TransactionalEventListener
  public void on(SaleReturned event) {
    EventScopes.run(
        event.metadata(),
        SERVICE,
        () ->
            once(
                () ->
                    ledger.postReturn(
                        new ReturnPosting(
                            event.returnUid(),
                            event.number(),
                            event.branchUid(),
                            localDate(event.metadata().tenantUid(), event.metadata().occurredAt()),
                            sum(event.lines(), SaleCompleted.Line::net),
                            sum(event.lines(), SaleCompleted.Line::tax),
                            cost(event.lines()),
                            event.refundMethod(),
                            event.refundCurrency(),
                            event.refundForeignAmount(),
                            event.fxDifference()))));
  }

  @Async
  @TransactionalEventListener
  public void on(GoodsReceived event) {
    EventScopes.run(
        event.metadata(),
        SERVICE,
        () ->
            once(
                () ->
                    ledger.postReceipt(
                        new ReceiptPosting(
                            event.receiptUid(),
                            event.number(),
                            event.branchUid(),
                            localDate(event.metadata().tenantUid(), event.metadata().occurredAt()),
                            baseTotal(event),
                            foreign(event) ? event.currency() : null,
                            foreign(event) ? event.total() : null))));
  }

  @Async
  @TransactionalEventListener
  public void on(SupplierReturned event) {
    EventScopes.run(
        event.metadata(),
        SERVICE,
        () ->
            once(
                () ->
                    ledger.postSupplierReturn(
                        event.returnUid(),
                        event.number(),
                        event.branchUid(),
                        localDate(event.metadata().tenantUid(), event.metadata().occurredAt()),
                        event.total())));
  }

  @Async
  @TransactionalEventListener
  public void on(StockAdjusted event) {
    if (event.value() == null || event.value().signum() == 0) {
      return;
    }
    EventScopes.run(
        event.metadata(),
        SERVICE,
        () ->
            once(
                () ->
                    ledger.postStockAdjustment(
                        event.movementUid(),
                        event.productName() + " (" + event.reasonCode() + ")",
                        event.metadata().branchUid(),
                        localDate(event.metadata().tenantUid(), event.metadata().occurredAt()),
                        event.value())));
  }

  private static void once(Runnable posting) {
    try {
      posting.run();
    } catch (DataIntegrityViolationException alreadyPosted) {
      LOG.info(
          "Journal entry already posted: {}", alreadyPosted.getMostSpecificCause().getMessage());
    }
  }

  private LocalDate localDate(UUID tenantUid, Instant occurredAt) {
    ZoneId zone =
        tenants.find(tenantUid).map(tenant -> ZoneId.of(tenant.timezone())).orElse(ZoneOffset.UTC);
    return LocalDate.ofInstant(occurredAt == null ? Instant.now() : occurredAt, zone);
  }

  /**
   * The base-currency value of the payments, by method: a foreign tender counts at the rate locked
   * at the sale (an event from before multi-currency has no base amount and is in base already).
   */
  static Map<String, BigDecimal> paymentsByMethod(List<SaleCompleted.Payment> payments) {
    Map<String, BigDecimal> byMethod = new LinkedHashMap<>();
    if (payments != null) {
      payments.forEach(
          payment ->
              byMethod.merge(
                  payment.method(),
                  payment.baseAmount() == null ? payment.amount() : payment.baseAmount(),
                  BigDecimal::add));
    }
    return byMethod;
  }

  /** The receipt's total in base; an event from before multi-currency is in base already. */
  static BigDecimal baseTotal(GoodsReceived event) {
    return event.baseTotal() == null ? event.total() : event.baseTotal();
  }

  /** Whether the receipt is in a currency other than its tenant's base. */
  private boolean foreign(GoodsReceived event) {
    return event.currency() != null
        && event.baseTotal() != null
        && tenants
            .find(event.metadata().tenantUid())
            .map(tenant -> !tenant.currency().equals(event.currency()))
            .orElse(false);
  }

  static BigDecimal cost(List<SaleCompleted.Line> lines) {
    return sum(lines, SaleCompleted.Line::cost);
  }

  static BigDecimal sum(
      List<SaleCompleted.Line> lines,
      java.util.function.Function<SaleCompleted.Line, BigDecimal> amount) {
    if (lines == null) {
      return BigDecimal.ZERO;
    }
    return lines.stream()
        .map(amount)
        .map(value -> value == null ? BigDecimal.ZERO : value)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }
}
