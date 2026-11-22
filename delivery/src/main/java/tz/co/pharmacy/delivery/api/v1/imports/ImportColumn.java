package tz.co.pharmacy.delivery.api.v1.imports;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * One column an import reads: its heading in English and Swahili (either is accepted), whether
 * every row needs it, what goes in it (for the template's Instructions sheet) and the API fields
 * whose refusals are about it, so a rule's refusal points at the right column.
 */
record ImportColumn(
    String key,
    String en,
    String sw,
    boolean required,
    String helpEn,
    String helpSw,
    List<String> fields) {

  static ImportColumn optional(String key, String en, String sw, String helpEn, String helpSw) {
    return new ImportColumn(key, en, sw, false, helpEn, helpSw, List.of(key));
  }

  static ImportColumn required(String key, String en, String sw, String helpEn, String helpSw) {
    return new ImportColumn(key, en, sw, true, helpEn, helpSw, List.of(key));
  }

  /** The same column, also answering for refusals about {@code more} fields. */
  ImportColumn about(String... more) {
    List<String> all =
        java.util.stream.Stream.concat(fields.stream(), Arrays.stream(more)).toList();
    return new ImportColumn(key, en, sw, required, helpEn, helpSw, all);
  }

  String heading(boolean swahili) {
    return swahili ? sw : en;
  }

  String help(boolean swahili) {
    return swahili ? helpSw : helpEn;
  }

  /**
   * Its English or Swahili heading, or its key (so a file made with an older heading still reads).
   */
  boolean matches(String heading) {
    String given = squash(heading);
    return given.equals(squash(en)) || given.equals(squash(sw)) || given.equals(squash(key));
  }

  /** Headings compare without case, spaces or punctuation: "VAT %" is "vat", "Code" is "code". */
  static String squash(String text) {
    return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
  }

  /** The allowed values of an enum, as the Instructions sheet lists them. */
  static String values(Class<? extends Enum<?>> type) {
    return Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.joining(", "));
  }
}
