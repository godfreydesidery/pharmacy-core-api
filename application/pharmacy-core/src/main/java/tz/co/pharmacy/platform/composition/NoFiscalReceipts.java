package tz.co.pharmacy.platform.composition;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.sales.ports.outbound.FiscalAnswer;
import tz.co.pharmacy.modules.sales.ports.outbound.FiscalReceipts;
import tz.co.pharmacy.modules.sales.ports.outbound.FiscalRequest;

/**
 * The default fiscal integration, {@code pharmacy.integrations.fiscal.provider=none}: no fiscal
 * document is issued, a sale is never due a receipt and keeps no fiscal reference.
 *
 * <p>This is the plug point for the tax authority. A TRA VFD (Tanzania) or KRA eTIMS (Kenya)
 * adapter implements {@link FiscalReceipts} with the authority's own specification and the
 * pharmacy's credentials, is enabled by its own provider value, such as
 * {@code @ConditionalOnProperty(name = "pharmacy.integrations.fiscal.provider", havingValue =
 * "tra-vfd")}, and replaces this one. {@link SimulatedFiscalReceipts} ({@code simulator}) stands in
 * for it in training, the simulated world and the tests.
 */
@Component
@ConditionalOnProperty(
    name = "pharmacy.integrations.fiscal.provider",
    havingValue = "none",
    matchIfMissing = true)
class NoFiscalReceipts implements FiscalReceipts {

  /** Never called: its documents are never due. */
  @Override
  public FiscalAnswer issue(FiscalRequest request) {
    throw new IllegalStateException("No fiscal integration is configured");
  }

  /** Its sales are never due a receipt, so nothing runs after their commit. */
  @Override
  public boolean enabled() {
    return false;
  }

  @Override
  public String provider() {
    return "none";
  }
}
