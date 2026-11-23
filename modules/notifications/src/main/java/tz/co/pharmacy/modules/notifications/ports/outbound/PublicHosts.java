package tz.co.pharmacy.modules.notifications.ports.outbound;

import java.util.Optional;

/**
 * Whether the server may connect to a host a pharmacy typed (its own SMTP server): the same
 * public-address rules webhook targets follow, so the server is never made to reach its own private
 * network.
 */
public interface PublicHosts {

  /** Why the server may not connect to {@code host}, or empty when it may. */
  Optional<String> problem(String host);
}
