package tz.co.pharmacy.platform.kernel;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.regex.Pattern;

/** Input checks every module shares; each refuses with {@code invalid_<field>} about the field. */
public final class Checks {

  private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
  private static final Pattern CODE = Pattern.compile("^[A-Z0-9]{2,12}$");

  private Checks() {}

  /** A trimmed, non-blank text of at most {@code max} characters. */
  public static String text(String value, String field, int max) {
    if (value == null || value.isBlank()) {
      throw Refusal.invalid("invalid_" + snake(field), field + " is required.", field);
    }
    String trimmed = value.trim();
    if (trimmed.length() > max) {
      throw Refusal.invalid(
          "invalid_" + snake(field), field + " must be at most " + max + " characters.", field);
    }
    return trimmed;
  }

  /** A trimmed text, or null when blank. */
  public static String optionalText(String value, String field, int max) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return text(value, field, max);
  }

  public static <T> T required(T value, String field) {
    if (value == null) {
      throw Refusal.invalid("invalid_" + snake(field), field + " is required.", field);
    }
    return value;
  }

  public static String email(String value, String field) {
    String email = text(value, field, 254).toLowerCase();
    if (!EMAIL.matcher(email).matches()) {
      throw Refusal.invalid("invalid_" + snake(field), field + " is not an email address.", field);
    }
    return email;
  }

  /** 2 to 12 upper-case letters or digits, such as {@code AFYA} or {@code HQ}. */
  public static String code(String value, String field) {
    String code = text(value, field, 12).toUpperCase();
    if (!CODE.matcher(code).matches()) {
      throw Refusal.invalid(
          "invalid_" + snake(field), field + " must be 2 to 12 letters or digits.", field);
    }
    return code;
  }

  public static int positive(Integer value, String field) {
    if (value == null || value <= 0) {
      throw Refusal.invalid("invalid_" + snake(field), field + " must be more than zero.", field);
    }
    return value;
  }

  public static BigDecimal notNegative(BigDecimal value, String field) {
    if (value == null || value.signum() < 0) {
      throw Refusal.invalid("invalid_" + snake(field), field + " must be zero or more.", field);
    }
    return Amounts.money(value);
  }

  public static BigDecimal percent(BigDecimal value, String field) {
    BigDecimal percent = value == null ? BigDecimal.ZERO : value;
    if (percent.signum() < 0 || percent.compareTo(BigDecimal.valueOf(100)) > 0) {
      throw Refusal.invalid(
          "invalid_" + snake(field), field + " must be between 0 and 100.", field);
    }
    return percent;
  }

  public static <T extends Collection<?>> T notEmpty(T values, String field) {
    if (values == null || values.isEmpty()) {
      throw Refusal.invalid("invalid_" + snake(field), field + " must not be empty.", field);
    }
    return values;
  }

  /** Parses an enum constant by name, in any case, refusing an unknown one. */
  public static <E extends Enum<E>> E oneOf(Class<E> type, String value, String field) {
    String name = text(value, field, 64).toUpperCase();
    try {
      return Enum.valueOf(type, name);
    } catch (IllegalArgumentException unknown) {
      throw Refusal.invalid(
          "invalid_" + snake(field), field + " is not one of the accepted values.", field);
    }
  }

  private static String snake(String field) {
    String last = field.contains(".") ? field.substring(field.lastIndexOf('.') + 1) : field;
    last = last.replaceAll("\\[\\d+]", "");
    return last.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
  }
}
