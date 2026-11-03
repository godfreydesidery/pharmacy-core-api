package tz.co.pharmacy.modules.access.ports.inbound;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PermissionsTest {

  @Test
  void everyPresetHoldsOnlyTenantPermissions() {
    Permissions.defaultRoles()
        .keySet()
        .forEach(
            code -> {
              assertThat(Permissions.preset(code)).isNotEmpty();
              assertThat(Permissions.TENANT_ALL).containsAll(Permissions.preset(code));
            });
  }

  @Test
  void administratorHoldsEveryTenantPermissionButNotPlatform() {
    assertThat(Permissions.preset("ADMIN")).containsExactlyElementsOf(Permissions.TENANT_ALL);
    assertThat(Permissions.TENANT_ALL).doesNotContain(Permissions.PLATFORM_MANAGE);
    assertThat(Permissions.ALL)
        .contains(Permissions.PLATFORM_MANAGE)
        .hasSize(Permissions.TENANT_ALL.size() + 1);
  }

  @Test
  void branchManagerCannotAdministerTheTenant() {
    assertThat(Permissions.preset("BRANCH_MANAGER"))
        .doesNotContain(
            Permissions.USERS_MANAGE,
            Permissions.SETTINGS_MANAGE,
            Permissions.INTEGRATIONS_MANAGE,
            Permissions.BRANCHES_MANAGE)
        .contains(Permissions.SALES_SELL, Permissions.FINANCE_MANAGE);
  }
}
