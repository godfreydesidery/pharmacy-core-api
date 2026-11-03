package tz.co.pharmacy.platform.persistence;

import jakarta.persistence.EntityManagerFactory;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/**
 * Starts every transaction with {@code set_config(..., true)} (transaction-local) of the tenant and
 * the user from {@link TenantContext}. The row-level security policies compare each row's {@code
 * tenant_uid} with {@code platform.current_tenant_uid()}; with no tenant the setting is empty and
 * matches no row.
 */
public class TenantAwareTransactionManager extends JpaTransactionManager {

  public static final String TENANT_SETTING = "pharmacy.tenant_uid";
  public static final String USER_SETTING = "pharmacy.user_uid";

  public TenantAwareTransactionManager(EntityManagerFactory entityManagerFactory) {
    super(entityManagerFactory);
  }

  @Override
  protected void doBegin(Object transaction, TransactionDefinition definition) {
    super.doBegin(transaction, definition);
    apply(
        new JdbcTemplate(getDataSource()),
        TenantContext.tenantUid().orElse(null),
        TenantContext.userUid().orElse(null));
  }

  static void apply(JdbcTemplate jdbc, UUID tenantUid, UUID userUid) {
    jdbc.queryForList(
        "select set_config(?, ?, true), set_config(?, ?, true)",
        TENANT_SETTING,
        tenantUid == null ? "" : tenantUid.toString(),
        USER_SETTING,
        userUid == null ? "" : userUid.toString());
  }
}
