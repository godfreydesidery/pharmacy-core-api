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
        .contains(Permissions.SALES_SELL, Permissions.FINANCE_MANAGE, Permissions.APPROVALS_GRANT);
  }

  @Test
  void sellingIsKeptApartFromRefundsStockAndCatalogueAndTheBooksFromTheBranch() {
    assertThat(Permissions.preset("PHARMACIST"))
        .doesNotContain(
            Permissions.SALES_REFUND, Permissions.INVENTORY_ADJUST, Permissions.CATALOGUE_MANAGE)
        .contains(Permissions.SALES_SELL, Permissions.PARTNERS_MANAGE);
    assertThat(Permissions.preset("BRANCH_MANAGER"))
        .doesNotContain(Permissions.FINANCE_PERIOD_LOCK);
    assertThat(Permissions.preset("ADMIN")).contains(Permissions.FINANCE_PERIOD_LOCK);
    assertThat(Permissions.preset("ACCOUNTANT")).contains(Permissions.FINANCE_PERIOD_LOCK);
  }

  @Test
  void creditLimitsAreSetByManagersAndFinanceNotByThePharmacist() {
    assertThat(Permissions.TENANT_ALL).contains(Permissions.PARTNERS_CREDIT_LIMIT);
    for (String role : java.util.List.of("ADMIN", "BRANCH_MANAGER", "ACCOUNTANT")) {
      assertThat(Permissions.preset(role)).as(role).contains(Permissions.PARTNERS_CREDIT_LIMIT);
    }
    for (String role : java.util.List.of("PHARMACIST", "CASHIER", "STORE_KEEPER")) {
      assertThat(Permissions.preset(role))
          .as(role)
          .doesNotContain(Permissions.PARTNERS_CREDIT_LIMIT);
    }
  }
}
