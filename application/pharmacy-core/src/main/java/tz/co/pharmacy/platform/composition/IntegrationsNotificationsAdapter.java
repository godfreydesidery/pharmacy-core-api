package tz.co.pharmacy.platform.composition;

import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.integrations.ports.outbound.SecretSealing;
import tz.co.pharmacy.modules.notifications.ports.inbound.Sealing;

/**
 * Seals webhook signing secrets (integrations) with the sealing message provider secrets use
 * (notifications): one AES-256-GCM sealing under {@code pharmacy.access.encryption-key} (INT-13).
 */
@Component
class IntegrationsNotificationsAdapter implements SecretSealing {

  private final Sealing sealing;

  IntegrationsNotificationsAdapter(Sealing sealing) {
    this.sealing = sealing;
  }

  @Override
  public String seal(String plain) {
    return sealing.seal(plain);
  }

  @Override
  public String open(String sealed) {
    return sealing.open(sealed);
  }
}
