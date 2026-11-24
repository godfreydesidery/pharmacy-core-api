package tz.co.pharmacy.platform.composition;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntSupplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantService;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.persistence.TenantContext;

/**
 * Tells {@link Amounts#money} the minor units of the pharmacy the current work runs for: its base
 * currency's (ISO 4217), 0 for a UGX pharmacy, 2 for a TZS one; 2 outside a pharmacy. The tenant is
 * the one row-level security sees ({@link TenantContext}), so a request, an event reaction and a
 * job each round to their own pharmacy's currency.
 *
 * <p>The base currency is read from the tenant registry once per transaction and kept with the
 * transaction (a transaction resource, released when it ends): nothing is kept between requests, so
 * every instance always rounds as the registry says. Outside a transaction it is read each time.
 */
@Component
class TenantMoneyScale implements IntSupplier, org.springframework.beans.factory.DisposableBean {

  private static final String KEY = TenantMoneyScale.class.getName() + ".scales";

  private final TenantService tenants;

  TenantMoneyScale(TenantService tenants) {
    this.tenants = tenants;
    Amounts.useScaleSource(this);
  }

  /**
   * Out of {@link Amounts} when the context closes: its tenant registry is closing with it. Another
   * context still running (tests start several) rounds with its own source again.
   */
  @Override
  public void destroy() {
    Amounts.releaseScaleSource(this);
  }

  @Override
  public int getAsInt() {
    UUID tenant = TenantContext.tenantUid().orElse(null);
    if (tenant == null) {
      return Amounts.DEFAULT_SCALE;
    }
    Map<UUID, Integer> scales = scalesOfTransaction();
    if (scales == null) {
      return lookUp(tenant);
    }
    Integer known = scales.get(tenant);
    if (known == null) {
      known = lookUp(tenant);
      scales.put(tenant, known);
    }
    return known;
  }

  private int lookUp(UUID tenant) {
    return tenants
        .baseCurrency(tenant)
        .map(Amounts::minorUnits)
        .map(units -> Math.min(units, Amounts.DEFAULT_SCALE))
        .orElse(Amounts.DEFAULT_SCALE);
  }

  /** The scales known in the current transaction, or null outside one. */
  @SuppressWarnings("unchecked")
  private static Map<UUID, Integer> scalesOfTransaction() {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      return null;
    }
    Object bound = TransactionSynchronizationManager.getResource(KEY);
    if (bound != null) {
      return (Map<UUID, Integer>) bound;
    }
    Map<UUID, Integer> scales = new HashMap<>();
    TransactionSynchronizationManager.bindResource(KEY, scales);
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            TransactionSynchronizationManager.unbindResourceIfPossible(KEY);
          }
        });
    return scales;
  }
}
