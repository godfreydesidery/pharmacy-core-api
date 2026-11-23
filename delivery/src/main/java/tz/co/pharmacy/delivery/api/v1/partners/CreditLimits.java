package tz.co.pharmacy.delivery.api.v1.partners;

import java.math.BigDecimal;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * A customer's credit limit is a finance decision (adversarial review FRD-17): whoever may manage
 * customers (partners.manage) may add and edit them, but setting or changing the limit needs {@code
 * finance.manage}, so the person who sells on credit does not also decide how much credit there is.
 * The rule sits on every way a limit is written (the customer form and the customer import).
 */
public final class CreditLimits {

  public static final String PERMISSION = "finance.manage";
  public static final String REASON = "credit_limit_needs_finance";

  private CreditLimits() {}

  /** Whether the caller may set or change credit limits. */
  public static boolean mayChange() {
    return ExecutionContext.permits(PERMISSION);
  }

  /** Whether {@code requested} is a different limit from {@code current} (null is none: 0). */
  public static boolean changes(BigDecimal current, BigDecimal requested) {
    return value(current).compareTo(value(requested)) != 0;
  }

  /** 403 credit_limit_needs_finance unless the caller may change limits or nothing changes. */
  public static void require(BigDecimal current, BigDecimal requested) {
    if (changes(current, requested) && !mayChange()) {
      throw Refusal.forbidden(
          REASON,
          "Setting or changing a customer's credit limit needs the finance permission"
              + " (finance.manage); leave the credit limit as it is.");
    }
  }

  private static BigDecimal value(BigDecimal amount) {
    return amount == null ? BigDecimal.ZERO : amount;
  }
}
