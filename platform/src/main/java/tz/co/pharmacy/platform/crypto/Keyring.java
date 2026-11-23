package tz.co.pharmacy.platform.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Seals secrets the application must read back (TOTP seeds, provider secrets, webhook signing
 * secrets) with AES-256-GCM (NIST SP 800-38D: a random 96-bit IV per value, a 128-bit tag) under
 * named keys, so a key can be rotated: new values are sealed with the purpose's active key, every
 * key in the keyring still opens the values that name it, and {@code KeyRotation} re-seals the old
 * ones until no value names the old key and it can be removed.
 *
 * <p>Two formats:
 *
 * <ul>
 *   <li>{@code v2:<keyId>:<base64(iv || ciphertext+tag)>}, with the purpose as associated data
 *       ({@code pharmacy/<purpose>}): a value names its key and opens only for its purpose;
 *   <li>unversioned {@code <base64(iv || ciphertext+tag)>}, without associated data: every value
 *       sealed before key ids existed, under {@code pharmacy.access.encryption-key} (id {@code
 *       legacy}). A purpose whose active key is still {@code legacy} keeps writing this format, so
 *       instances of the previous release, running beside this one during a rolling deploy, can
 *       still read what this one writes.
 * </ul>
 *
 * Base64 has no {@code ':'}, so the two never mix up. Each key's AES key is the SHA-256 of its
 * configured text, as the legacy key always was. Purposes use separate configured keys rather than
 * keys derived (HKDF) from one master: each can then be rotated, or revoked after a leak, on its
 * own, and one secret per purpose maps directly to one ECS secret.
 */
@Component
public class Keyring {

  public static final String VERSION = "v2";

  private static final Logger LOG = LoggerFactory.getLogger(Keyring.class);
  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final Map<String, SecretKeySpec> keys = new LinkedHashMap<>();
  private final Map<Purpose, String> active = new EnumMap<>(Purpose.class);

  @Autowired
  public Keyring(Environment environment) {
    this(KeyringSettings.read(environment));
  }

  public Keyring(KeyringSettings settings) {
    List<String> invalid = settings.invalid();
    if (!invalid.isEmpty()) {
      throw new IllegalStateException(
          "The keyring is misconfigured:\n - " + String.join("\n - ", invalid));
    }
    settings.material().forEach((id, text) -> keys.put(id, aesKey(text)));
    for (Purpose purpose : Purpose.values()) {
      active.put(purpose, settings.activeId(purpose));
    }
    if (KeyringSettings.LOCAL_KEY.equals(settings.material().get(KeyringSettings.LEGACY))) {
      LOG.warn(
          "{} is the local default; set it (or a key per purpose) in production.",
          KeyringSettings.LEGACY_PROPERTY);
    }
    LOG.info("Keyring: keys {}, active {}", keys.keySet(), active);
  }

  /** Seals {@code plain} for {@code purpose} with the purpose's active key. */
  public String seal(Purpose purpose, byte[] plain) {
    String id = active.get(purpose);
    SecretKeySpec key = keys.get(id);
    try {
      byte[] iv = new byte[IV_BYTES];
      RANDOM.nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      boolean legacy = KeyringSettings.LEGACY.equals(id);
      if (!legacy) {
        cipher.updateAAD(associatedData(purpose));
      }
      byte[] sealed = cipher.doFinal(plain);
      String body =
          Base64.getEncoder()
              .encodeToString(
                  ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array());
      return legacy ? body : VERSION + ":" + id + ":" + body;
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("could not encrypt", failure);
    }
  }

  public String seal(Purpose purpose, String plain) {
    return seal(purpose, plain.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Opens a value sealed for {@code purpose}. Throws {@link IllegalStateException} when its key is
   * not in the keyring, or the value was sealed under other key material or for another purpose.
   */
  public byte[] open(Purpose purpose, String sealedText) {
    String id = keyIdOf(sealedText);
    SecretKeySpec key = keys.get(id);
    if (key == null) {
      throw new IllegalStateException(
          "could not decrypt: the value was sealed under the key '"
              + id
              + "', which is not in the keyring");
    }
    try {
      boolean versioned = sealedText.startsWith(VERSION + ":");
      String body = versioned ? sealedText.substring(sealedText.indexOf(':', 3) + 1) : sealedText;
      byte[] all = Base64.getDecoder().decode(body);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
      if (versioned) {
        cipher.updateAAD(associatedData(purpose));
      }
      return cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
    } catch (GeneralSecurityException | IllegalArgumentException | IndexOutOfBoundsException e) {
      throw new IllegalStateException(
          "could not decrypt with the key '" + id + "': other key material, or another purpose?");
    }
  }

  public String openText(Purpose purpose, String sealedText) {
    return new String(open(purpose, sealedText), StandardCharsets.UTF_8);
  }

  /** Opens a value and seals it again with the purpose's active key. */
  public String reseal(Purpose purpose, String sealedText) {
    return seal(purpose, open(purpose, sealedText));
  }

  /** The id of the key a value names: {@code legacy} for an unversioned one. */
  public static String keyIdOf(String sealedText) {
    if (sealedText != null && sealedText.startsWith(VERSION + ":")) {
      int end = sealedText.indexOf(':', VERSION.length() + 1);
      if (end > VERSION.length() + 1) {
        return sealedText.substring(VERSION.length() + 1, end);
      }
    }
    return KeyringSettings.LEGACY;
  }

  /** The id of the key that seals the purpose's new values. */
  public String activeKey(Purpose purpose) {
    return active.get(purpose);
  }

  /** Every purpose's active key id, by purpose id. */
  public Map<String, String> activeKeys() {
    Map<String, String> ids = new LinkedHashMap<>();
    active.forEach((purpose, id) -> ids.put(purpose.id(), id));
    return ids;
  }

  /** The ids of the keys the keyring holds. */
  public Set<String> keyIds() {
    return Set.copyOf(keys.keySet());
  }

  private static byte[] associatedData(Purpose purpose) {
    return ("pharmacy/" + purpose.id()).getBytes(StandardCharsets.UTF_8);
  }

  private static SecretKeySpec aesKey(String text) {
    try {
      return new SecretKeySpec(
          MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)),
          "AES");
    } catch (GeneralSecurityException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
