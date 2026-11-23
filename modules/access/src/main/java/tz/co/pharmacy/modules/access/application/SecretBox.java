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

  /** The access module's sealed columns, for the key re-seal and its status. */
  @Configuration(proxyBeanMethods = false)
  static class SealedColumns {

    @Bean
    SealedColumn sealedTotpSecrets() {
      return new SealedColumn("access.users", "totp_secret", Purpose.TOTP, false, null);
    }
  }
}
