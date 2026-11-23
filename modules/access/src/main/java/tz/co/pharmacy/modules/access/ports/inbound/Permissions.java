package tz.co.pharmacy.modules.access.ports.inbound;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The permission catalogue (contract §3) and the permissions of the default roles every new tenant
 * starts with. A root administrator holds every permission in every tenant.
 */
public final class Permissions {

  public static final String PLATFORM_MANAGE = "platform.manage";
  public static final String DASHBOARD_VIEW = "dashboard.view";
  public static final String SALES_SELL = "sales.sell";
  public static final String SALES_VIEW = "sales.view";
  public static final String SALES_REFUND = "sales.refund";
  public static final String SALES_DISCOUNT = "sales.discount";
  public static final String SALES_CREDIT = "sales.credit";
  public static final String SALES_PRICE_PLAN = "sales.price_plan";
  public static final String SALES_VOUCHER = "sales.voucher";
  public static final String INVENTORY_VIEW = "inventory.view";
  public static final String INVENTORY_ADJUST = "inventory.adjust";
  public static final String INVENTORY_TRANSFER = "inventory.transfer";
  public static final String PURCHASING_VIEW = "purchasing.view";
  public static final String PURCHASING_MANAGE = "purchasing.manage";
  public static final String PURCHASING_RECEIVE = "purchasing.receive";
  public static final String CATALOGUE_VIEW = "catalogue.view";
  public static final String CATALOGUE_MANAGE = "catalogue.manage";
  public static final String PARTNERS_VIEW = "partners.view";
  public static final String PARTNERS_MANAGE = "partners.manage";
  public static final String PARTNERS_CREDIT_LIMIT = "partners.credit_limit";
  public static final String FINANCE_VIEW = "finance.view";
  public static final String FINANCE_MANAGE = "finance.manage";
  public static final String FINANCE_WRITE_OFF = "finance.write_off";
  public static final String FINANCE_PERIOD_LOCK = "finance.period_lock";
  public static final String REPORTS_VIEW = "reports.view";
  public static final String USERS_MANAGE = "users.manage";
  public static final String BRANCHES_MANAGE = "branches.manage";
  public static final String SETTINGS_MANAGE = "settings.manage";
  public static final String INTEGRATIONS_MANAGE = "integrations.manage";
  public static final String APPROVALS_GRANT = "approvals.grant";

  /**
   * Bulk exports of people's data: customers with their phones and insurance numbers, patients and
   * their prescriptions (the controlled-drugs register). Personal data under Tanzania's Personal
   * Data Protection Act 2022 leaves the system only with this (adversarial review ISO-10).
   */
  public static final String DATA_EXPORT = "data.export";

  private static final List<PermissionView> CATALOGUE =
      List.of(
          new PermissionView(PLATFORM_MANAGE, "platform", "Manage tenants and root administrators"),
          new PermissionView(DASHBOARD_VIEW, "dashboard", "View the dashboard"),
          new PermissionView(SALES_SELL, "sales", "Sell at the point of sale"),
          new PermissionView(SALES_VIEW, "sales", "View sales history"),
          new PermissionView(SALES_REFUND, "sales", "Record sales returns and refunds"),
          new PermissionView(SALES_DISCOUNT, "sales", "Apply a discount at the point of sale"),
          new PermissionView(
              SALES_CREDIT, "sales", "Sell on account: credit sales and balances put on account"),
          new PermissionView(
              SALES_PRICE_PLAN, "sales", "Sell under another pricing plan than the customer's own"),
          new PermissionView(
              SALES_VOUCHER, "sales", "Issue vouchers (prepaid value, sold for money)"),
          new PermissionView(INVENTORY_VIEW, "inventory", "View stock, batches, expiry, movements"),
          new PermissionView(
              INVENTORY_ADJUST, "inventory", "Adjust stock and record opening stock"),
          new PermissionView(INVENTORY_TRANSFER, "inventory", "Transfer stock between branches"),
          new PermissionView(
              PURCHASING_VIEW, "purchasing", "View purchase orders and goods receipts"),
          new PermissionView(PURCHASING_MANAGE, "purchasing", "Create and cancel purchase orders"),
          new PermissionView(PURCHASING_RECEIVE, "purchasing", "Record goods receipts"),
          new PermissionView(CATALOGUE_VIEW, "catalogue", "View products and categories"),
          new PermissionView(
              CATALOGUE_MANAGE, "catalogue", "Manage products, categories and manufacturers"),
          new PermissionView(PARTNERS_VIEW, "partners", "View customers and suppliers"),
          new PermissionView(PARTNERS_MANAGE, "partners", "Manage customers and suppliers"),
          new PermissionView(
              PARTNERS_CREDIT_LIMIT, "partners", "Set and change customers' credit limits"),
          new PermissionView(FINANCE_VIEW, "finance", "View expenses, accounts and the journal"),
          new PermissionView(FINANCE_MANAGE, "finance", "Record expenses"),
          new PermissionView(
              FINANCE_WRITE_OFF, "finance", "Write off bad debts on customer accounts"),
          new PermissionView(FINANCE_PERIOD_LOCK, "finance", "Lock and unlock the books' period"),
          new PermissionView(REPORTS_VIEW, "reports", "View reports"),
          new PermissionView(USERS_MANAGE, "administration", "Manage users and roles"),
          new PermissionView(BRANCHES_MANAGE, "administration", "Manage branches"),
          new PermissionView(SETTINGS_MANAGE, "administration", "Manage tenant settings"),
          new PermissionView(INTEGRATIONS_MANAGE, "administration", "Manage webhooks"),
          new PermissionView(
              APPROVALS_GRANT,
              "administration",
              "Approve others' actions above the pharmacy's limit (refunds, write-offs, discounts)"),
          new PermissionView(DATA_EXPORT, "administration", "Export people's data in bulk"));

  /** Every permission code. */
  public static final Set<String> ALL =
      CATALOGUE.stream()
          .map(PermissionView::code)
          .collect(Collectors.toCollection(LinkedHashSet::new));

  /** Every permission a tenant role may hold: all but {@code platform.manage}. */
  public static final Set<String> TENANT_ALL =
      ALL.stream()
          .filter(code -> !PLATFORM_MANAGE.equals(code))
          .collect(Collectors.toCollection(LinkedHashSet::new));

  /** The default system roles, code to name, in the order they are listed. */
  private static final Map<String, String> DEFAULT_ROLES = defaultRoleNames();

  private Permissions() {}

  public static List<PermissionView> catalogue() {
    return CATALOGUE;
  }

  /** The tenant permissions, for the role editor. */
  public static List<PermissionView> tenantCatalogue() {
    return CATALOGUE.stream().filter(view -> !PLATFORM_MANAGE.equals(view.code())).toList();
  }

  /** The default roles' codes and names. */
  public static Map<String, String> defaultRoles() {
    return DEFAULT_ROLES;
  }

  /**
   * The version of the presets below. A pharmacy's admin decides once per version whether to apply
   * them to its built-in roles (the owner's decision of 23 Nov 2026: existing pharmacies keep their
   * roles until their admin chooses); a later change of the presets takes a new version.
   *
   * <p>{@link #DATA_EXPORT} did not take one: access V18 gives it to every existing pharmacy's
   * built-in Administrator, Branch Manager and Accountant, exactly as these presets do, so the
   * built-in roles differ from the presets no more than before and there is nothing new to offer.
   */
  public static final String PRESETS_VERSION = "2026-11-23";

  /**
   * The permissions of a default role; empty for any other code. Selling is kept apart from
   * refunding, adjusting stock and editing the catalogue (Pharmacist), and closing the books from
   * running a branch (Branch Manager): segregation of duties, adversarial review FRD-6, FRD-10,
   * FRD-17. Exporting people's data in bulk is for the Administrator, Branch Manager and Accountant
   * only (ISO-10).
   */
  public static Set<String> preset(String roleCode) {
    return switch (roleCode) {
      case "ADMIN" -> TENANT_ALL;
      case "BRANCH_MANAGER" ->
          without(
              TENANT_ALL,
              USERS_MANAGE,
              SETTINGS_MANAGE,
              INTEGRATIONS_MANAGE,
              BRANCHES_MANAGE,
              FINANCE_PERIOD_LOCK);
      case "PHARMACIST" ->
          ordered(
              DASHBOARD_VIEW,
              SALES_SELL,
              SALES_VIEW,
              SALES_DISCOUNT,
              SALES_CREDIT,
              INVENTORY_VIEW,
              CATALOGUE_VIEW,
              PARTNERS_VIEW,
              PARTNERS_MANAGE,
              PURCHASING_VIEW,
              PURCHASING_RECEIVE,
              REPORTS_VIEW);
      case "CASHIER" ->
          ordered(
              DASHBOARD_VIEW,
              SALES_SELL,
              SALES_VIEW,
              CATALOGUE_VIEW,
              PARTNERS_VIEW,
              INVENTORY_VIEW);
      case "STORE_KEEPER" ->
          ordered(
              DASHBOARD_VIEW,
              INVENTORY_VIEW,
              INVENTORY_ADJUST,
              INVENTORY_TRANSFER,
              PURCHASING_VIEW,
              PURCHASING_MANAGE,
              PURCHASING_RECEIVE,
              CATALOGUE_VIEW,
              PARTNERS_VIEW);
      case "ACCOUNTANT" ->
          ordered(
              DASHBOARD_VIEW,
              FINANCE_VIEW,
              FINANCE_MANAGE,
              FINANCE_WRITE_OFF,
              FINANCE_PERIOD_LOCK,
              REPORTS_VIEW,
              SALES_VIEW,
              PURCHASING_VIEW,
              PARTNERS_VIEW,
              PARTNERS_CREDIT_LIMIT,
              DATA_EXPORT);
      default -> Set.of();
    };
  }

  private static Set<String> ordered(String... codes) {
    return new LinkedHashSet<>(List.of(codes));
  }

  private static Set<String> without(Set<String> codes, String... excluded) {
    Set<String> result = new LinkedHashSet<>(codes);
    List.of(excluded).forEach(result::remove);
    return result;
  }

  private static Map<String, String> defaultRoleNames() {
    Map<String, String> roles = new LinkedHashMap<>();
    roles.put("ADMIN", "Administrator");
    roles.put("BRANCH_MANAGER", "Branch Manager");
    roles.put("PHARMACIST", "Pharmacist");
    roles.put("CASHIER", "Cashier");
    roles.put("STORE_KEEPER", "Store Keeper");
    roles.put("ACCOUNTANT", "Accountant");
    return java.util.Collections.unmodifiableMap(roles);
  }
}
