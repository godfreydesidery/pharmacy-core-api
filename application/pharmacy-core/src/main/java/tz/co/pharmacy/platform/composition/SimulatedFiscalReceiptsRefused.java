package tz.co.pharmacy.platform.composition;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Stops the start when the fiscal simulator is asked for outside the {@code sim}, {@code local} and
 * {@code test} profiles: a pharmacy must never take simulated numbers for TRA's receipts.
 */
@Component
@Profile("!sim & !local & !test")
@ConditionalOnProperty(name = "pharmacy.integrations.fiscal.provider", havingValue = "simulator")
class SimulatedFiscalReceiptsRefused {

  SimulatedFiscalReceiptsRefused() {
    throw new IllegalStateException(
        "pharmacy.integrations.fiscal.provider=simulator is allowed only under the sim, local or"
            + " test profile; set PHARMACY_FISCAL_PROVIDER to none or to a real adapter");
  }
}
