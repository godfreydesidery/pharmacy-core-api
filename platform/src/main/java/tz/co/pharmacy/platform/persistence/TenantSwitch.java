package tz.co.pharmacy.platform.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Switches the tenant inside a transaction already under way, for the one use case that needs it:
 * registering a tenant, which writes the tenant registry row at platform scope and then the new
 * tenant's own rows (branches, settings, roles, the first administrator) in the same transaction.
 * The work's writes are flushed before the previous tenant is restored, so row-level security
 * checks them as the new tenant.
 */
@Component
public class TenantSwitch {

  private final JdbcTemplate jdbc;

  @PersistenceContext private EntityManager entityManager;

  public TenantSwitch(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public <T> T within(UUID tenantUid, Supplier<T> work) {
    UUID previous = TenantContext.tenantUid().orElse(null);
    UUID user = TenantContext.userUid().orElse(null);
    entityManager.flush();
    TenantContext.setTenant(tenantUid);
    TenantAwareTransactionManager.apply(jdbc, tenantUid, user);
    try {
      T result = work.get();
      entityManager.flush();
      return result;
    } finally {
      TenantContext.setTenant(previous);
      TenantAwareTransactionManager.apply(jdbc, previous, user);
    }
  }
}
