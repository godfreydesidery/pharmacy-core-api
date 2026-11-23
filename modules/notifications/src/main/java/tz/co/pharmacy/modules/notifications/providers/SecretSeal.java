package tz.co.pharmacy.modules.notifications.providers;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.platform.crypto.Keyring;
import tz.co.pharmacy.platform.crypto.Purpose;
import tz.co.pharmacy.platform.crypto.SealedColumn;

/**
 * Seals provider secrets (API keys, auth tokens, SMTP passwords) with the platform {@link Keyring}
 * under the {@code providers} purpose's active key. A secret sealed under an older key still opens
 * while that key is in the keyring.
 */
@Component
public class SecretSeal {

  private final Keyring keyring;

  SecretSeal(Keyring keyring) {
    this.keyring = keyring;
  }

  public String seal(String plain) {
    return keyring.seal(Purpose.PROVIDERS, plain);
  }

  /** Throws {@link IllegalStateException} when no key in the keyring opens the value. */
  public String open(String sealedText) {
    return keyring.openText(Purpose.PROVIDERS, sealedText);
  }

  /** The notifications module's sealed columns, for the key re-seal and its status. */
  @Configuration(proxyBeanMethods = false)
  static class SealedColumns {

    /** The platform's providers (no pharmacy) and each pharmacy's own. */
    @Bean
    SealedColumn sealedProviderSecrets() {
      return new SealedColumn(
          "notifications.provider_settings", "secrets", Purpose.PROVIDERS, true, null);
    }
  }
}
