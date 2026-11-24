package tz.co.pharmacy.modules.access.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.platform.crypto.Keyring;
import tz.co.pharmacy.platform.crypto.Purpose;
import tz.co.pharmacy.platform.crypto.SealedColumn;

/**
 * Encrypts secrets the module must read back, such as TOTP seeds, with the platform {@link Keyring}
 * under the {@code totp} purpose's active key. A copy of the database alone gives no seed; a seed
 * sealed under an older key still opens while that key is in the keyring.
 */
@Component
class SecretBox {

  private final Keyring keyring;

  SecretBox(Keyring keyring) {
    this.keyring = keyring;
  }

  String seal(byte[] plain) {
    return keyring.seal(Purpose.TOTP, plain);
  }

  /** Throws {@link IllegalStateException} when no key in the keyring opens the value. */
  byte[] open(String sealedText) {
    return keyring.open(Purpose.TOTP, sealedText);
  }

  /**
   * The keyed hash (HMAC-SHA-256 with the {@code codes} pepper) a short code is stored as: recovery
   * codes and SMS codes carry 20 to 40 bits, so a bare SHA-256 of one is found by trying them all;
   * without the pepper, which is kept out of the database, it is not (AUTH-16).
   */
  String codeHash(String text) {
    return keyring.mac(Purpose.CODES, text);
  }

  /**
   * Every form {@code text} may be stored as: its keyed hash under each key of the keyring, and
   * {@code legacy}, the unsalted SHA-256 hash kept before keyed hashes, so a code issued before
   * keeps working.
   */
  java.util.List<String> codeHashes(String text, String legacy) {
    java.util.List<String> all =
        new java.util.ArrayList<>(keyring.macCandidates(Purpose.CODES, text));
    all.add(legacy);
    return all;
  }

  /** Whether {@code stored} is the hash of {@code text}: keyed, or the legacy {@code legacy}. */
  boolean codeMatches(String stored, String text, String legacy) {
    if (stored == null) {
      return false;
    }
    if (Keyring.isMac(stored)) {
      return keyring.macMatches(Purpose.CODES, stored, text);
    }
    return java.security.MessageDigest.isEqual(
        stored.getBytes(java.nio.charset.StandardCharsets.UTF_8),
        legacy.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  /** The access module's sealed columns, for the key re-seal and its status. */
  @Configuration(proxyBeanMethods = false)
  static class SealedColumns {

    @Bean
    SealedColumn sealedTotpSecrets() {
      return new SealedColumn("access.users", "totp_secret", Purpose.TOTP, false, null);
    }

    /** Counted (never re-sealed), so a key some recovery code still names stays in the keyring. */
    @Bean
    SealedColumn keyedRecoveryCodes() {
      return SealedColumn.keyedHashes("access.recovery_codes", "code_hash", Purpose.CODES, false);
    }

    @Bean
    SealedColumn keyedPhoneCodes() {
      return SealedColumn.keyedHashes("access.phone_codes", "code_hash", Purpose.CODES, false);
    }
  }
}
