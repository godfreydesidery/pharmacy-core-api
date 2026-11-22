package tz.co.pharmacy.platform.composition;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import tz.co.pharmacy.modules.fx.ports.inbound.ExchangeRates;
import tz.co.pharmacy.modules.fx.ports.inbound.RateCommand;
import tz.co.pharmacy.modules.tenancy.events.TenantRegistered;
import tz.co.pharmacy.modules.tenancy.ports.inbound.ChangeSettingsCommand;
import tz.co.pharmacy.modules.tenancy.ports.inbound.SettingsService;
import tz.co.pharmacy.platform.bus.EventScopes;
import tz.co.pharmacy.platform.kernel.Clocks;

/**
 * The demo tenant of a local run also accepts US dollars and Kenyan shillings, with today's rates
 * recorded by hand, so the POS and purchasing can be tried in a foreign currency. It reacts to the
 * demo tenant's registration ({@link DevelopmentSeed}) and touches no other tenant.
 */
@Component
@ConditionalOnProperty(name = "pharmacy.seed.demo", havingValue = "true")
class DemoCurrencySeed {

  private final SettingsService settings;
  private final ExchangeRates rates;

  DemoCurrencySeed(SettingsService settings, ExchangeRates rates) {
    this.settings = settings;
    this.rates = rates;
  }

  @Async
  @TransactionalEventListener
  public void on(TenantRegistered event) {
    if (!"AFYA".equals(event.code())) {
      return;
    }
    EventScopes.run(
        event.metadata(),
        "demo-seed",
        () -> {
          settings.change(
              new ChangeSettingsCommand(
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  List.of("USD", "KES"),
                  null,
                  null,
                  null,
                  null));
          LocalDate today = LocalDate.now(Clocks.clock());
          rates.record(new RateCommand("USD", "TZS", new BigDecimal("2650"), today));
          rates.record(new RateCommand("KES", "TZS", new BigDecimal("20.45"), today));
        });
  }
}
