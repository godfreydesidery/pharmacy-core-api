package tz.co.pharmacy.platform.crypto;

import java.util.regex.Pattern;

/**
 * A column that holds values sealed by the {@link Keyring}, declared by the module that owns it (a
 * bean), so the re-seal and its status reach every sealed value without knowing the modules.
 *
 * @param table the schema-qualified table, with a {@code bigint id} primary key
 * @param column the column holding sealed values (null when there is none)
 * @param purpose what the values are for: they are re-sealed with that purpose's active key
 * @param tenantScoped whether the rows carry {@code tenant_uid} under row-level security, so each
 *     pharmacy is read in turn (and the platform's own rows, {@code tenant_uid} null); otherwise
 *     the table is read once, at platform scope
 * @param plainPrefix the prefix of values kept in plain text from before sealing ({@code whsec_}),
 *     or null: the re-seal seals them too
 * @param keyedHash the values are keyed hashes ({@link Keyring#mac}), which cannot be re-sealed:
 *     they are only counted, so a key still naming some is never reported removable. Older plain
 *     hashes count as {@link SealedValues#UNKEYED}.
 */
public record SealedColumn(
    String table,
    String column,
    Purpose purpose,
    boolean tenantScoped,
    String plainPrefix,
    boolean keyedHash) {

  public SealedColumn(
      String table, String column, Purpose purpose, boolean tenantScoped, String plainPrefix) {
    this(table, column, purpose, tenantScoped, plainPrefix, false);
  }

  /** A column of keyed hashes ({@link Keyring#mac}) for {@code purpose}. */
  public static SealedColumn keyedHashes(
      String table, String column, Purpose purpose, boolean tenantScoped) {
    return new SealedColumn(table, column, purpose, tenantScoped, null, true);
  }

  private static final Pattern TABLE = Pattern.compile("[a-z_]+\\.[a-z_]+");
  private static final Pattern COLUMN = Pattern.compile("[a-z_]+");

  public SealedColumn {
    if (!TABLE.matcher(table).matches() || !COLUMN.matcher(column).matches()) {
      throw new IllegalArgumentException("not a plain schema.table and column: " + table + column);
    }
    if (plainPrefix != null && !plainPrefix.matches("[A-Za-z0-9_]+")) {
      throw new IllegalArgumentException("plain prefix: " + plainPrefix);
    }
  }

  /** {@code schema.table.column}, as the status names it. */
  public String name() {
    return table + "." + column;
  }
}
