package tz.co.pharmacy.platform.composition;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import tz.co.pharmacy.modules.finance.events.ExpenseRecorded;
import tz.co.pharmacy.modules.purchasing.events.GoodsReceived;
import tz.co.pharmacy.modules.reporting.ports.inbound.ExpenseFact;
import tz.co.pharmacy.modules.reporting.ports.inbound.ProductFact;
import tz.co.pharmacy.modules.reporting.ports.inbound.Projections;
import tz.co.pharmacy.modules.reporting.ports.inbound.PurchaseFact;
import tz.co.pharmacy.modules.reporting.ports.inbound.SaleFact;
import tz.co.pharmacy.modules.sales.events.SaleCompleted;
import tz.co.pharmacy.modules.sales.events.SaleReturned;
import tz.co.pharmacy.platform.bus.EventScopes;

/**
 * Feeds the reporting read models from sales, sale returns, goods receipts and expenses. The
 * payment mix records the money kept: the cash paid less the change given back.
 */
@Component
public class ReportingReactions {

  private static final String SERVICE = "reporting-projection";

  private final Projections projections;

  public ReportingReactions(Projections projections) {
    this.projections = projections;
  }

  @Async
  @TransactionalEventListener
  public void on(SaleCompleted event) {
    Map<String, BigDecimal> payments = LedgerReactions.paymentsByMethod(event.payments());
    BigDecimal change = event.changeDue() == null ? BigDecimal.ZERO : event.changeDue();
    if (change.signum() > 0) {
      String from =
          payments.containsKey("CASH")
              ? "CASH"
              : payments.keySet().stream().findFirst().orElse("CASH");
      payments.merge(from, change.negate(), BigDecimal::add);
    }
    SaleFact fact =
        new SaleFact(
            event.metadata().eventId(),
            event.metadata().occurredAt(),
            event.branchUid(),
            event.subtotal(),
            event.discountTotal(),
            event.taxTotal(),
            event.total().subtract(event.taxTotal()),
            LedgerReactions.cost(event.lines()),
            products(event.lines()),
            payments,
            1);
    EventScopes.run(event.metadata(), SERVICE, () -> projections.sale(fact));
  }

  @Async
  @TransactionalEventListener
  public void on(SaleReturned event) {
    BigDecimal net = LedgerReactions.sum(event.lines(), SaleCompleted.Line::net);
    BigDecimal tax = LedgerReactions.sum(event.lines(), SaleCompleted.Line::tax);
    SaleFact fact =
        new SaleFact(
            event.metadata().eventId(),
            event.metadata().occurredAt(),
            event.branchUid(),
            net,
            BigDecimal.ZERO,
            tax,
            net,
            LedgerReactions.cost(event.lines()),
            products(event.lines()),
            refundsByMethod(event),
            -1);
    EventScopes.run(event.metadata(), SERVICE, () -> projections.sale(fact));
  }

  @Async
  @TransactionalEventListener
  public void on(GoodsReceived event) {
    PurchaseFact fact =
        new PurchaseFact(
            event.metadata().eventId(),
            event.metadata().occurredAt(),
            event.branchUid(),
            LedgerReactions.baseTotal(event));
    EventScopes.run(event.metadata(), SERVICE, () -> projections.purchase(fact));
  }

  @Async
  @TransactionalEventListener
  public void on(ExpenseRecorded event) {
    ExpenseFact fact =
        new ExpenseFact(
            event.metadata().eventId(),
            event.expenseDate(),
            event.branchUid(),
            event.category(),
            event.amount());
    EventScopes.run(event.metadata(), SERVICE, () -> projections.expense(fact));
  }

  /** The refund by the way it went back (the sale's own methods), for the day's tender totals. */
  static Map<String, BigDecimal> refundsByMethod(SaleReturned event) {
    Map<String, BigDecimal> byMethod = new java.util.LinkedHashMap<>();
    event
        .shares()
        .forEach(
            share ->
                byMethod.merge(
                    share.method() == null ? "CASH" : share.method(),
                    share.amount() == null ? BigDecimal.ZERO : share.amount(),
                    BigDecimal::add));
    return byMethod;
  }

  private static List<ProductFact> products(List<SaleCompleted.Line> lines) {
    if (lines == null) {
      return List.of();
    }
    return lines.stream()
        .map(
            line ->
                new ProductFact(
                    line.productUid(),
                    line.productName(),
                    line.quantity(),
                    line.net(),
                    line.cost()))
        .toList();
  }
}
