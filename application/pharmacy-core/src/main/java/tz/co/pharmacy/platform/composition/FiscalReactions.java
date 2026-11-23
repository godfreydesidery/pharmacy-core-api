package tz.co.pharmacy.platform.composition;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import tz.co.pharmacy.modules.sales.events.SaleCompleted;
import tz.co.pharmacy.modules.sales.ports.inbound.SaleService;
import tz.co.pharmacy.platform.bus.EventScopes;

/**
 * Issues a sale's fiscal receipt after the sale committed (PERF-1), never inside the transaction
 * that holds the branch's sale number and the stock. The event publication registry is the outbox:
 * when the tax authority fails or is slow to answer, the call throws, the publication stays
 * incomplete and the event-resubmission job delivers it again, so a receipt is never lost; the sale
 * shows {@code fiscalDue} until it is issued. Registered only with a fiscal integration ({@code
 * pharmacy.integrations.fiscal.provider} other than {@code none}).
 */
@Component
@ConditionalOnExpression("'${pharmacy.integrations.fiscal.provider:none}' != 'none'")
public class FiscalReactions {

  private static final String SERVICE = "fiscal-receipts";

  private final SaleService sales;

  public FiscalReactions(SaleService sales) {
    this.sales = sales;
  }

  @Async
  @TransactionalEventListener
  public void on(SaleCompleted event) {
    EventScopes.run(event.metadata(), SERVICE, () -> sales.fiscalise(event.saleUid()));
  }
}
