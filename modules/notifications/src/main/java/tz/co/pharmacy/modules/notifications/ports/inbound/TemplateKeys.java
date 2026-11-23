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
  public static final String ACCESS_VERIFICATION_CODE = "access.verification_code";
  public static final String ACCESS_EMAIL_CONFIRM = "access.email_confirm";
  public static final String ACCESS_EMAIL_IN_USE = "access.email_in_use";
  public static final String ACCESS_EMAIL_CHANGED = "access.email_changed";
  public static final String ACCESS_PASSWORD_CHANGED = "access.password_changed";
  public static final String ACCESS_PHONE_REMOVED = "access.phone_removed";
  public static final String ACCESS_RECOVERY_STARTED = "access.recovery_started";
  public static final String ACCESS_RECOVERY_LINK = "access.recovery_link";
  public static final String ACCESS_LOGIN_LINKED = "access.login_linked";
  public static final String ACCESS_PASSKEY_ADDED = "access.passkey_added";
  public static final String ACCESS_TWO_FACTOR_ON = "access.two_factor_on";
  public static final String ACCESS_TWO_FACTOR_OFF = "access.two_factor_off";
  public static final String ACCESS_PHONE_VERIFIED = "access.phone_verified";
  public static final String TENANT_WELCOME = "tenant.welcome";
  public static final String SUBSCRIPTION_DUE = "tenant.subscription_due";
  public static final String SUBSCRIPTION_PAST_DUE = "tenant.subscription_past_due";

  /**
   * In-app, to the administrators, once a month: the plan's SMS on the platform's account are used.
   */
  public static final String SMS_CAP_REACHED = "tenant.sms_cap_reached";

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
          ACCESS_VERIFICATION_CODE,
          ACCESS_EMAIL_CONFIRM,
          ACCESS_EMAIL_IN_USE,
          ACCESS_EMAIL_CHANGED,
          ACCESS_PASSWORD_CHANGED,
          ACCESS_PHONE_REMOVED,
          ACCESS_RECOVERY_STARTED,
          ACCESS_RECOVERY_LINK,
          ACCESS_LOGIN_LINKED,
          ACCESS_PASSKEY_ADDED,
          ACCESS_TWO_FACTOR_ON,
          ACCESS_TWO_FACTOR_OFF,
          ACCESS_PHONE_VERIFIED,
          TENANT_WELCOME,
          SUBSCRIPTION_DUE,
          SUBSCRIPTION_PAST_DUE,
          SMS_CAP_REACHED);

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
      case TENANT_WELCOME, SUBSCRIPTION_DUE, SUBSCRIPTION_PAST_DUE, SMS_CAP_REACHED -> "tenant";
      default -> "access";
    };
  }
}
