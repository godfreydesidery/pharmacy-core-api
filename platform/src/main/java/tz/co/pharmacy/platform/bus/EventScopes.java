package tz.co.pharmacy.platform.bus;

import tz.co.pharmacy.platform.kernel.EventMetadata;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.persistence.TenantContext;

/**
 * Runs an event listener's work as a named platform service in the event's tenant and branch. A
 * listener runs on its own thread after the publishing transaction committed; it must call this
 * before any transaction starts, so the transaction sees the event's tenant and nothing else.
 *
 * <pre>
 *   &#64;Async &#64;TransactionalEventListener
 *   void on(SaleCompleted event) {
 *     EventScopes.run(event.metadata(), "ledger-posting", () -> postings.postSale(...));
 *   }
 * </pre>
 */
public final class EventScopes {

  private EventScopes() {}

  public static void run(EventMetadata metadata, String serviceName, Runnable work) {
    ExecutionContext.callAs(
        ExecutionContext.Frame.service(
            serviceName, metadata.correlationId(), metadata.tenantUid(), metadata.branchUid()),
        () ->
            TenantContext.callAs(
                metadata.tenantUid(),
                () -> {
                  work.run();
                  return null;
                }));
  }
}
