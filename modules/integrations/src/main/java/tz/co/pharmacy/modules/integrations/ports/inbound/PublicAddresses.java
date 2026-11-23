package tz.co.pharmacy.modules.integrations.ports.inbound;

import java.util.Optional;

/**
 * The server-side request forgery rules webhook targets follow, for any host a pharmacy may make
 * the server connect to (such as its own SMTP server): the host must be a public name or address,
 * and every address it resolves to must be public. Loopback, private, carrier-grade NAT,
 * link-local, unique-local, unspecified, multicast and reserved addresses are refused, as are
 * localhost and *.local, *.internal, *.localdomain, *.localhost and single-label names.
 */
public interface PublicAddresses {

  /** Why the server may not connect to {@code host}, or empty when it may. */
  Optional<String> problem(String host);
}
