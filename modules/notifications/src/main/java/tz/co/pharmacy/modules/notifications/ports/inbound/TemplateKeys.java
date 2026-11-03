package tz.co.pharmacy.modules.notifications.ports.inbound;

import java.util.List;

/** The template keys every module may send, and the preference category each belongs to. */
public final class TemplateKeys {

  public static final String STOCK_LOW = "stock.low";
  public static final String STOCK_EXPIRING = "stock.expiring";
  public static final String SALE_RECEIPT = "sale.receipt";
  public static final String DAILY_SUMMARY = "daily.summary";
  public static final String CUSTOMER_OVERDUE = "customer.overdue";
  public static final String ACCESS_INVITATION = "access.invitation";
  public static final String ACCESS_PASSWORD_RESET = "access.password_reset";
  public static final String ACCESS_NEW_SIGN_IN = "access.new_sign_in";
  public static final String ACCESS_TWO_FACTOR_CODE = "access.two_factor_code";
  public static final String TENANT_WELCOME = "tenant.welcome";

  public static final List<String> ALL =
      List.of(
          STOCK_LOW,
          STOCK_EXPIRING,
          SALE_RECEIPT,
          DAILY_SUMMARY,
          CUSTOMER_OVERDUE,
          ACCESS_INVITATION,
          ACCESS_PASSWORD_RESET,
          ACCESS_NEW_SIGN_IN,
          ACCESS_TWO_FACTOR_CODE,
          TENANT_WELCOME);

  /** Preference categories; {@code access} messages are always sent. */
  public static final List<String> CATEGORIES =
      List.of("stock", "sales", "reports", "customers", "access", "tenant");

  private TemplateKeys() {}

  /** The preference category of a key, such as {@code stock} for {@code stock.low}. */
  public static String category(String key) {
    return switch (key) {
      case STOCK_LOW, STOCK_EXPIRING -> "stock";
      case SALE_RECEIPT -> "sales";
      case DAILY_SUMMARY -> "reports";
      case CUSTOMER_OVERDUE -> "customers";
      case TENANT_WELCOME -> "tenant";
      default -> "access";
    };
  }
}
