package tz.co.pharmacy.platform.composition;

import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.integrations.ports.outbound.SecretSealing;
import tz.co.pharmacy.platform.crypto.Keyring;
import tz.co.pharmacy.platform.crypto.Purpose;

/**
 * Seals webhook signing secrets (integrations) with the platform keyring under the {@code webhooks}
 * purpose's active key (INT-13, OPS-5).
 */
@Component
class IntegrationsSealingAdapter implements SecretSealing {

  private final Keyring keyring;

  IntegrationsSealingAdapter(Keyring keyring) {
    this.keyring = keyring;
  }

  @Override
  public String seal(String plain) {
    return keyring.seal(Purpose.WEBHOOKS, plain);
  }

  @Override
  public String open(String sealed) {
    return keyring.openText(Purpose.WEBHOOKS, sealed);
  }
}
