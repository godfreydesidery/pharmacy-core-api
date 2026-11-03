package tz.co.pharmacy.platform.composition;

import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.sales.ports.outbound.FiscalReceipts;

/**
 * The default fiscal integration, {@code pharmacy.integrations.fiscal.provider=none}: no fiscal
 * receipt is issued and a sale keeps no fiscal reference.
 *
 * <p>This is the plug point for the tax authority. A TRA VFD (Tanzania) or KRA eTIMS (Kenya)
 * adapter implements {@link FiscalReceipts}, is enabled by its own provider value, such as
 * {@code @ConditionalOnProperty(name = "pharmacy.integrations.fiscal.provider", havingValue =
 * "tra-vfd")}, and replaces this one.
 */
@Component
@ConditionalOnProperty(
    name = "pharmacy.integrations.fiscal.provider",
    havingValue = "none",
    matchIfMissing = true)
class NoFiscalReceipts implements FiscalReceipts {

  @Override
  public Optional<String> issue(String saleNumber, BigDecimal total, BigDecimal taxTotal) {
    return Optional.empty();
  }
}
