package tz.co.pharmacy.modules.reporting.ports.inbound;

/**
 * Feeds the read models; composition calls it from event listeners, in the event's tenant. Each
 * call is idempotent per event id: a redelivered event changes nothing.
 */
public interface Projections {

  void sale(SaleFact fact);

  void purchase(PurchaseFact fact);

  void expense(ExpenseFact fact);
}
