package tz.co.pharmacy.platform.crypto;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

/**
 * The keyring's configuration, as Spring reads it from the environment (each value one ECS secret,
 * or one key of a JSON secret, injected as an environment variable):
 *
 * <ul>
 *   <li>{@code pharmacy.keys.<id>} ({@code PHARMACY_KEYS_<ID>}): a named key, at least 32 random
 *       characters ({@code openssl rand -base64 32}). The id is 1 to 32 lower-case letters and
 *       digits, starting with a letter, such as {@code k2026a};
 *   <li>{@code pharmacy.access.encryption-key} ({@code PHARMACY_ACCESS_ENCRYPTIONKEY}): the key of
 *       every value sealed before key ids existed, under the reserved id {@code legacy};
 *   <li>{@code pharmacy.sealing.<purpose>} ({@code PHARMACY_SEALING_TOTP}, {@code ..._PROVIDERS},
 *       {@code ..._WEBHOOKS}): the id of the key that seals new values of the purpose. Unset, the
 *       purpose keeps sealing with {@code legacy}.
 * </ul>
 *
 * <p>Every key, whichever purpose seals with it, opens any value that names it: a purpose's active
 * key changes without making any stored value unreadable.
 */
public record KeyringSettings(
    String legacy, Map<String, String> keys, Map<Purpose, String> active) {

  /** The id of {@code pharmacy.access.encryption-key}, the key of unversioned values. */
  public static final String LEGACY = "legacy";

  /** The published development key: the local and test profiles' default, refused elsewhere. */
  public static final String LOCAL_KEY = "local-development-only-encryption-key";

  public static final String LEGACY_PROPERTY = "pharmacy.access.encryption-key";
  public static final String LEGACY_VARIABLE = "PHARMACY_ACCESS_ENCRYPTIONKEY";

  private static final Pattern KEY_ID = Pattern.compile("[a-z][a-z0-9]{0,31}");

  public KeyringSettings {
    keys = Map.copyOf(keys);
    active = active.isEmpty() ? Map.of() : Map.copyOf(new EnumMap<>(active));
  }

  /** Reads the keyring's configuration from Spring's environment (properties or variables). */
  public static KeyringSettings read(Environment environment) {
    Map<String, String> keys = new TreeMap<>();
    if (environment instanceof ConfigurableEnvironment configurable) {
      Binder.get(configurable)
          .bind("pharmacy.keys", Bindable.mapOf(String.class, String.class))
          .orElse(Map.of())
          .forEach((id, value) -> keys.put(id.toLowerCase(Locale.ROOT), value));
    }
    Map<Purpose, String> active = new EnumMap<>(Purpose.class);
    for (Purpose purpose : Purpose.values()) {
      String id = environment.getProperty(purpose.property(), "").trim();
      if (!id.isEmpty()) {
        active.put(purpose, id.toLowerCase(Locale.ROOT));
      }
    }
    return new KeyringSettings(environment.getProperty(LEGACY_PROPERTY, ""), keys, active);
  }

  /** The id of the key that seals the purpose's new values. */
  public String activeId(Purpose purpose) {
    return active.getOrDefault(purpose, LEGACY);
  }

  /** Whether some purpose still seals with the legacy key. */
  public boolean legacyActive() {
    for (Purpose purpose : Purpose.values()) {
      if (LEGACY.equals(activeId(purpose))) {
        return true;
      }
    }
    return false;
  }

  /**
   * The keys as the keyring holds them: every named key, and {@code legacy} when it is set. Unset,
   * {@code legacy} is the published development key while a purpose still seals with it (a laptop,
   * the tests; production refuses it), and absent once every purpose has a key of its own.
   */
  public Map<String, String> material() {
    Map<String, String> material = new LinkedHashMap<>();
    if (!legacy.isBlank()) {
      material.put(LEGACY, legacy);
    } else if (legacyActive()) {
      material.put(LEGACY, LOCAL_KEY);
    }
    material.putAll(keys);
    return material;
  }

  /** Mistakes that stop any start, in every profile: the keyring would not work. */
  public List<String> invalid() {
    List<String> problems = new ArrayList<>();
    keys.forEach(
        (id, value) -> {
          if (LEGACY.equals(id)) {
            problems.add(
                "pharmacy.keys.legacy is reserved: the legacy key is "
                    + LEGACY_PROPERTY
                    + " ("
                    + LEGACY_VARIABLE
                    + ").");
          } else if (!KEY_ID.matcher(id).matches()) {
            problems.add(
                "pharmacy.keys."
                    + id
                    + ": a key id is 1 to 32 lower-case letters and digits, starting with a letter.");
          } else if (value == null || value.isBlank()) {
            problems.add(variable(id) + " is empty.");
          }
        });
    for (Purpose purpose : Purpose.values()) {
      String id = activeId(purpose);
      if (!LEGACY.equals(id) && !keys.containsKey(id)) {
        problems.add(
            purpose.property()
                + " ("
                + purpose.variable()
                + ") names the key '"
                + id
                + "', which is not in the keyring: set "
                + variable(id)
                + ".");
      }
    }
    return problems;
  }

  /** What a server must not run with: a published or short key, or a missing legacy key. */
  public List<String> productionProblems(int minLength) {
    List<String> problems = new ArrayList<>(invalid());
    if (legacy.isBlank() || legacy.equals(LOCAL_KEY)) {
      if (legacyActive() || legacy.equals(LOCAL_KEY)) {
        problems.add(
            LEGACY_PROPERTY
                + " ("
                + LEGACY_VARIABLE
                + ") is not set; it is the published default.");
      }
    } else if (legacy.length() < minLength) {
      problems.add(tooShort(LEGACY_PROPERTY + " (" + LEGACY_VARIABLE + ")", minLength));
    }
    Map<String, String> seen = new LinkedHashMap<>();
    if (!legacy.isBlank()) {
      seen.put(legacy, LEGACY);
    }
    keys.forEach(
        (id, value) -> {
          if (value == null || value.isBlank() || LEGACY.equals(id)) {
            return;
          }
          String twin = seen.putIfAbsent(value, id);
          if (value.equals(LOCAL_KEY)) {
            problems.add(variable(id) + " is the published default.");
          } else if (twin != null) {
            problems.add(
                variable(id)
                    + " holds the same value as the key '"
                    + twin
                    + "': each key must be new random material.");
          } else if (value.length() < minLength) {
            problems.add(tooShort(variable(id), minLength));
          }
        });
    return problems;
  }

  private static String tooShort(String name, int minLength) {
    return name
        + " is shorter than "
        + minLength
        + " characters; use a random value of at least that length.";
  }

  /** The property and environment variable of a named key. */
  public static String variable(String id) {
    return "pharmacy.keys." + id + " (PHARMACY_KEYS_" + id.toUpperCase(Locale.ROOT) + ")";
  }
}
