package tz.co.pharmacy.platform.composition;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import tz.co.pharmacy.modules.catalogue.ports.inbound.CatalogueService;
import tz.co.pharmacy.modules.tenancy.events.StandardVatRateChanged;
import tz.co.pharmacy.platform.bus.EventScopes;

/**
 * The pharmacy's standard VAT rate changed (STD-2): every STANDARD product is sold at it from now
 * on. After the settings commit, through the outbox, so a failure is delivered again.
 */
@Component
public class TaxReactions {

  private static final String SERVICE = "standard-vat-rate";

  private final CatalogueService catalogue;

  public TaxReactions(CatalogueService catalogue) {
    this.catalogue = catalogue;
  }

  @Async
  @TransactionalEventListener
  public void on(StandardVatRateChanged event) {
    EventScopes.run(
        event.metadata(), SERVICE, () -> catalogue.applyStandardVatRate(event.ratePercent()));
  }
}
