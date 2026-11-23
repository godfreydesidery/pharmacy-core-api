package tz.co.pharmacy.modules.finance.application;

import java.util.Arrays;
import java.util.Locale;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * How the pharmacy pays money out (an expense, a supplier payment), and the money account it comes
 * from. Only these: a free-text method once let "CREDIT" credit customer receivables and a typo
 * post to cash (adversarial review MON-14). Money paid out never touches a receivable.
 */
enum PaidFrom {
  CASH(Postings.CASH),
  BANK_TRANSFER(Postings.BANK),
  CARD(Postings.BANK),
  CHEQUE(Postings.BANK),
  MOBILE_MONEY(Postings.MOBILE_MONEY);

  private final String accountCode;

  PaidFrom(String accountCode) {
    this.accountCode = accountCode;
  }

  String accountCode() {
    return accountCode;
  }

  /** The method named, or {@code otherwise} when blank; 422 invalid_payment_method otherwise. */
  static PaidFrom parse(String method, PaidFrom otherwise, String field) {
    if (method == null || method.isBlank()) {
      return otherwise;
    }
    String name = method.trim().toUpperCase(Locale.ROOT);
    return Arrays.stream(values())
        .filter(value -> value.name().equals(name))
        .findFirst()
        .orElseThrow(
            () ->
                Refusal.invalid(
                    "invalid_payment_method",
                    "Pay with one of " + Arrays.toString(values()) + ".",
                    field));
  }
}
