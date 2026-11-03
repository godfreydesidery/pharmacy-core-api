package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tz.co.pharmacy.support.IntegrationTest;

/**
 * Tenant isolation is enforced by PostgreSQL, not only by the application: as the application role,
 * a connection with no tenant set sees no tenant row and may not write one, and every tenant table
 * has a forced row-level security policy.
 */
class RowLevelSecurityTest extends IntegrationTest {

  @Autowired DataSource dataSource;

  @Test
  @DisplayName("every table with tenant_uid has forced row-level security")
  void everyTenantTableIsProtected() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    List<Map<String, Object>> unprotected =
        jdbc.queryForList(
            """
            select c.table_schema, c.table_name
              from information_schema.columns c
              join pg_class t on t.relname = c.table_name
              join pg_namespace n on n.oid = t.relnamespace and n.nspname = c.table_schema
             where c.column_name = 'tenant_uid'
               and (not t.relrowsecurity or not t.relforcerowsecurity)
            """);
    assertThat(unprotected).isEmpty();
  }

  @Test
  @DisplayName("without a tenant, the application role sees no tenant rows and cannot write one")
  void failsClosed() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    assertThat(jdbc.queryForObject("select count(*) from tenancy.branches", Long.class)).isZero();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into platform.document_sequences (tenant_uid, sequence_key, last_value)"
                        + " values (gen_random_uuid(), 'X', 1)"))
        .rootCause()
        .hasMessageContaining("row-level security");
  }

  @Test
  @DisplayName("the application role is neither superuser nor bypasses row-level security")
  void applicationRoleIsUnprivileged() {
    Map<String, Object> role =
        new JdbcTemplate(dataSource)
            .queryForMap(
                "select rolsuper, rolbypassrls from pg_roles where rolname = current_user");
    assertThat(role.get("rolsuper")).isEqualTo(false);
    assertThat(role.get("rolbypassrls")).isEqualTo(false);
  }
}
