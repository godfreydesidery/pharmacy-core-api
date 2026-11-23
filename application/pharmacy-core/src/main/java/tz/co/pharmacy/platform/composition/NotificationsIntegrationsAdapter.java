package tz.co.pharmacy.platform.composition;

import java.util.Optional;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.integrations.ports.inbound.PublicAddresses;
import tz.co.pharmacy.modules.notifications.ports.outbound.PublicHosts;

/**
 * Binds the SMTP host rule of notifications to the public-address rules webhook targets follow
 * (integrations), so there is one SSRF rule for every host a pharmacy types (INT-2).
 */
@Component
class NotificationsIntegrationsAdapter implements PublicHosts {

  private final PublicAddresses addresses;

  NotificationsIntegrationsAdapter(PublicAddresses addresses) {
    this.addresses = addresses;
  }

  @Override
  public Optional<String> problem(String host) {
    return addresses.problem(host);
  }
}
