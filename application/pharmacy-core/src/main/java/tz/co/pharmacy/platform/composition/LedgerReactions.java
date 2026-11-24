package tz.co.pharmacy.platform.composition;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import tz.co.pharmacy.modules.inventory.events.BatchCostAveraged;
import tz.co.pharmacy.modules.inventory.events.StockAdjusted;
import tz.co.pharmacy.modules.purchasing.events.GoodsReceived;
import tz.co.pharmacy.modules.purchasing.events.SupplierReturned;
import tz.co.pharmacy.modules.sales.events.SaleCompleted;
import tz.co.pharmacy.modules.sales.events.SaleReturnReleased;
import tz.co.pharmacy.modules.sales.events.SaleReturned;
import tz.co.pharmacy.modules.sales.events.VoucherIssued;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantService;
import tz.co.pharmacy.platform.bus.EventScopes;
import tz.co.pharmacy.platform.kernel.Clocks;

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
        () -> {
          LocalDate date = localDate(event.metadata().tenantUid(), event.metadata().occurredAt());
          for (ReturnPosting posting : returnPostings(event, date)) {
            once(() -> ledger.postReturn(posting));
          }
        });
  }

  /**
   * A return refunds the way its sale was paid, so it posts one entry per way: each credits that
   * way's account (cash, bank, mobile money; 1100 for the customer's account; 1110 for the
   * insurer's share, off the claim; 2300 for a voucher) with its share, and debits sales returns
   * and tax payable in proportion. The first entry is the return's own (it carries the cost and a
   * foreign refund's exchange difference); the others are keyed by the return and the method, so
   * each posts once. An event from before the split posts one entry, as before.
   */
  static List<ReturnPosting> returnPostings(SaleReturned event, LocalDate date) {
    Map<String, BigDecimal> shares = new LinkedHashMap<>();
    String first = event.refundMethod();
    if (event.shares().stream().anyMatch(share -> share.method().equals(first))) {
      shares.put(first, BigDecimal.ZERO);
    }
    event.shares().forEach(share -> shares.merge(share.method(), share.amount(), BigDecimal::add));
    shares.values().removeIf(amount -> amount == null || amount.signum() <= 0);
    BigDecimal net = sum(event.lines(), SaleCompleted.Line::net);
    BigDecimal tax = sum(event.lines(), SaleCompleted.Line::tax);
    if (shares.size() <= 1) {
      return List.of(
          new ReturnPosting(
              event.returnUid(),
              event.number(),
              event.branchUid(),
              date,
              net,
              tax,
              restockedCost(event),
              shares.isEmpty() ? event.refundMethod() : shares.keySet().iterator().next(),
              event.refundCurrency(),
              event.refundForeignAmount(),
              event.fxDifference()));
    }
    BigDecimal total = shares.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    List<ReturnPosting> postings = new ArrayList<>();
    BigDecimal taxLeft = tax;
    int i = 0;
    for (Map.Entry<String, BigDecimal> share : shares.entrySet()) {
      boolean last = ++i == shares.size();
      BigDecimal shareTax =
          last
              ? taxLeft
              : tax.multiply(share.getValue())
                  .divide(total, 2, java.math.RoundingMode.HALF_UP)
                  .min(share.getValue());
      taxLeft = taxLeft.subtract(shareTax);
      boolean own = postings.isEmpty();
      postings.add(
          new ReturnPosting(
              own
                  ? event.returnUid()
                  : UUID.nameUUIDFromBytes(
                      (event.returnUid() + "/" + share.getKey())
                          .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
              event.number(),
              event.branchUid(),
              date,
              share.getValue().subtract(shareTax),
              shareTax,
              own ? restockedCost(event) : BigDecimal.ZERO,
              share.getKey(),
              own ? event.refundCurrency() : null,
              own ? event.refundForeignAmount() : null,
              own ? event.fxDifference() : null));
    }
    return postings;
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
                            foreign(event) ? event.total() : null,
                            baseVat(event)))));
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
  public void on(BatchCostAveraged event) {
    if (event.roundingValue() == null || event.roundingValue().signum() == 0) {
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
                        event.productName()
                            + " batch "
                            + event.batchNumber()
                            + " (cost averaged to "
                            + event.averagedUnitCost().toPlainString()
                            + ")",
                        event.metadata().branchUid(),
                        localDate(event.metadata().tenantUid(), event.metadata().occurredAt()),
                        event.roundingValue())));
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
    return LocalDate.ofInstant(occurredAt == null ? Clocks.now() : occurredAt, zone);
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

  /** The receipt's input VAT in base (STD-2); none in events from before it was kept. */
  static BigDecimal baseVat(GoodsReceived event) {
    return event.baseVatTotal() == null ? BigDecimal.ZERO : event.baseVatTotal();
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

  /**
   * The cost a return books back to inventory: its lines' cost less what it holds in quarantine,
   * which is out of stock until released ({@code sale.return_released} books it then).
   */
  static BigDecimal restockedCost(SaleReturned event) {
    BigDecimal held = event.heldCost() == null ? BigDecimal.ZERO : event.heldCost();
    return cost(event.lines()).subtract(held);
  }

  /**
   * Units a return held in quarantine were released (back on the shelf, or restocked and written
   * off): their cost comes back from cost of goods sold to inventory, Dr 1200 / Cr 5000, once per
   * return line; a write-off then posts its own stock loss.
   */
  @Async
  @TransactionalEventListener
  public void on(SaleReturnReleased event) {
    if (event.cost() == null || event.cost().signum() == 0) {
      return;
    }
    EventScopes.run(
        event.metadata(),
        SERVICE,
        () ->
            once(
                () ->
                    ledger.postReturn(
                        new ReturnPosting(
                            event.lineUid(),
                            event.number(),
                            event.branchUid(),
                            localDate(event.metadata().tenantUid(), event.metadata().occurredAt()),
                            BigDecimal.ZERO,
                            BigDecimal.ZERO,
                            event.cost(),
                            "CASH"))));
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
