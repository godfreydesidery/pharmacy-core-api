package tz.co.pharmacy.platform.persistence;

import jakarta.persistence.EntityManagerFactory;
import java.time.Duration;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/**
 * Starts every transaction with {@code set_config(..., true)} (transaction-local) of the tenant and
 * the user from {@link TenantContext}. The row-level security policies compare each row's {@code
 * tenant_uid} with {@code platform.current_tenant_uid()}; with no tenant the setting is empty and
 * matches no row.
 *
 * <p>A transaction that declares a timeout ({@code @Transactional(timeout = 120)}, {@code
 * TransactionTemplate#setTimeout}), or that a scheduled job begins ({@link StatementTimeout}), also
 * sets {@code statement_timeout} for itself in the same statement ({@code SET LOCAL}): an export or
 * report may run longer than the connections' 30 seconds ({@link DatabaseTimeouts}) without raising
 * it for anyone else.
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
    Duration timeout =
        definition.getTimeout() > 0
            ? Duration.ofSeconds(definition.getTimeout())
            : StatementTimeout.current();
    UUID tenantUid = TenantContext.tenantUid().orElse(null);
    UUID userUid = TenantContext.userUid().orElse(null);
    JdbcTemplate jdbc = new JdbcTemplate(getDataSource());
    if (timeout == null) {
      apply(jdbc, tenantUid, userUid);
    } else {
      jdbc.queryForList(
          "select set_config(?, ?, true), set_config(?, ?, true),"
              + " set_config('statement_timeout', ?, true)",
          TENANT_SETTING,
          tenantUid == null ? "" : tenantUid.toString(),
          USER_SETTING,
          userUid == null ? "" : userUid.toString(),
          StatementTimeout.setting(timeout));
    }
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
