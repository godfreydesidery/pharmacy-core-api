package tz.co.pharmacy.platform.composition;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import tz.co.pharmacy.modules.catalogue.events.ProductChanged;
import tz.co.pharmacy.modules.integrations.ports.inbound.Webhooks;
import tz.co.pharmacy.modules.inventory.events.StockAdjusted;
import tz.co.pharmacy.modules.inventory.events.StockLow;
import tz.co.pharmacy.modules.purchasing.events.GoodsReceived;
import tz.co.pharmacy.modules.sales.events.SaleCompleted;
import tz.co.pharmacy.modules.sales.events.SaleReturned;
import tz.co.pharmacy.modules.tenancy.events.TenantRegistered;
import tz.co.pharmacy.platform.bus.EventScopes;
import tz.co.pharmacy.platform.kernel.DomainEvent;
import tz.co.pharmacy.platform.kernel.EventMetadata;

/**
 * Delivers the events external systems may subscribe to, to the tenant's webhooks. Each event type
 * has its own listener, so one slow endpoint never holds back the ledger or the reports.
 */
@Component
public class WebhookReactions {

  private static final String SERVICE = "webhook-dispatch";

  private final Webhooks webhooks;

  public WebhookReactions(Webhooks webhooks) {
    this.webhooks = webhooks;
  }

  @Async
  @TransactionalEventListener
  public void on(SaleCompleted event) {
    dispatch(event.metadata(), "sale.completed", event);
  }

  @Async
  @TransactionalEventListener
  public void on(SaleReturned event) {
    dispatch(event.metadata(), "sale.returned", event);
  }

  @Async
  @TransactionalEventListener
  public void on(GoodsReceived event) {
    dispatch(event.metadata(), "goods.received", event);
  }

  @Async
  @TransactionalEventListener
  public void on(StockAdjusted event) {
    dispatch(event.metadata(), "stock.adjusted", event);
  }

  @Async
  @TransactionalEventListener
  public void on(StockLow event) {
    dispatch(event.metadata(), "stock.low", event);
  }

  @Async
  @TransactionalEventListener
  public void on(ProductChanged event) {
    dispatch(event.metadata(), "product.changed", event);
  }

  /** A new tenant has no webhooks yet, but its event is delivered in its own scope all the same. */
  @Async
  @TransactionalEventListener
  public void on(TenantRegistered event) {
    EventMetadata metadata = event.metadata();
    EventMetadata inTenant =
        new EventMetadata(
            metadata.eventId(),
            metadata.type(),
            event.tenantUid(),
            null,
            metadata.occurredAt(),
            metadata.actorReference(),
            metadata.correlationId());
    dispatch(inTenant, "tenant.registered", event);
  }

  private void dispatch(EventMetadata metadata, String type, DomainEvent event) {
    if (metadata.tenantUid() == null) {
      return;
    }
    EventScopes.run(
        metadata,
        SERVICE,
        () -> webhooks.dispatch(metadata.eventId(), type, metadata.occurredAt(), event));
  }
}
