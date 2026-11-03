package tz.co.pharmacy.modules.access.application;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts secrets the module must read back, such as TOTP seeds, with AES-256-GCM under a key
 * derived from {@code pharmacy.access.encryption-key}. A copy of the database alone gives no seed.
 */
@Component
class SecretBox {

  private static final Logger log = LoggerFactory.getLogger(SecretBox.class);
  private static final String LOCAL_KEY = "local-development-only-encryption-key";
  private static final int IV_BYTES = 12;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final SecretKeySpec key;

  SecretBox(@Value("${pharmacy.access.encryption-key:" + LOCAL_KEY + "}") String secret) {
    if (LOCAL_KEY.equals(secret)) {
      log.warn("pharmacy.access.encryption-key is the local default; set it in production.");
    }
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
      this.key = new SecretKeySpec(digest, "AES");
    } catch (GeneralSecurityException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  String seal(byte[] plain) {
    try {
      byte[] iv = new byte[IV_BYTES];
      RANDOM.nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
      byte[] sealed = cipher.doFinal(plain);
      return Base64.getEncoder()
          .encodeToString(
              ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array());
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("could not encrypt", failure);
    }
  }

  byte[] open(String sealedText) {
    try {
      byte[] all = Base64.getDecoder().decode(sealedText);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, IV_BYTES));
      return cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("could not decrypt: wrong pharmacy.access.encryption-key?");
    }
  }
}
