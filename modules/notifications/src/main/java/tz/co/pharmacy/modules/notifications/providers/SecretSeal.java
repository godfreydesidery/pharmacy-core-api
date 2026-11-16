package tz.co.pharmacy.modules.notifications.providers;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Seals provider secrets (API keys, auth tokens, SMTP passwords) with AES-256-GCM under a key
 * derived from {@code pharmacy.access.encryption-key}, the same key the access module seals TOTP
 * seeds with.
 */
@Component
public class SecretSeal {

  private static final String LOCAL_KEY = "local-development-only-encryption-key";
  private static final int IV_BYTES = 12;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final SecretKeySpec key;

  SecretSeal(@Value("${pharmacy.access.encryption-key:" + LOCAL_KEY + "}") String secret) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
      this.key = new SecretKeySpec(digest, "AES");
    } catch (GeneralSecurityException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public String seal(String plain) {
    try {
      byte[] iv = new byte[IV_BYTES];
      RANDOM.nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
      byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder()
          .encodeToString(
              ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array());
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("could not encrypt", failure);
    }
  }

  public String open(String sealedText) {
    try {
      byte[] all = Base64.getDecoder().decode(sealedText);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, IV_BYTES));
      return new String(
          cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("could not decrypt: wrong pharmacy.access.encryption-key?");
    }
  }
}
