package tz.co.pharmacy.modules.integrations.application;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.integrations.ports.inbound.PublicAddresses;

/**
 * Where a webhook may point. A pharmacy admin types the URL, so the server must never be made to
 * call itself or its private network (SSRF): the URL must be https to a public host, and every
 * address the host resolves to must be public, both when the webhook is saved and again just before
 * each delivery (a name can be re-pointed later). Loopback, private (10/8, 172.16/12, 192.168/16),
 * carrier-grade NAT (100.64/10), link-local (169.254/16, fe80::/10), unique-local (fc00::/7),
 * unspecified and multicast addresses are refused, as are localhost and *.local, *.internal and
 * *.localhost names. Hosts listed in {@code pharmacy.integrations.webhooks.allowed-hosts} (empty by
 * default; for local development only) skip these checks and may use plain http.
 *
 * <p>The host rules alone are the {@link PublicAddresses} port, which SMTP provider settings reuse.
 */
@Component
class WebhookTargets implements PublicAddresses {

  /** Why a URL was refused, or null when it may be called. */
  record Verdict(String problem) {
    boolean allowed() {
      return problem == null;
    }
  }

  private final Set<String> allowedHosts;
  private final Resolver resolver;

  /** Name resolution, replaceable in tests. */
  interface Resolver {
    InetAddress[] resolve(String host) throws UnknownHostException;
  }

  @Autowired
  WebhookTargets(@Value("${pharmacy.integrations.webhooks.allowed-hosts:}") List<String> allowed) {
    this(allowed, InetAddress::getAllByName);
  }

  WebhookTargets(List<String> allowed, Resolver resolver) {
    this.allowedHosts =
        allowed == null
            ? Set.of()
            : allowed.stream()
                .map(host -> host.trim().toLowerCase(Locale.ROOT))
                .filter(host -> !host.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    this.resolver = resolver;
  }

  Verdict check(String url) {
    URI uri;
    try {
      uri = URI.create(url.trim());
    } catch (IllegalArgumentException notAUri) {
      return new Verdict("The address is not a valid URL.");
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    if (host.isEmpty() || uri.getUserInfo() != null) {
      return new Verdict("The address must name a host, without a user name or password.");
    }
    if (allowedHosts.contains(host)) {
      return scheme.equals("https") || scheme.equals("http")
          ? new Verdict(null)
          : new Verdict("The address must be an http or https URL.");
    }
    if (!scheme.equals("https")) {
      return new Verdict("The address must be an https URL.");
    }
    return new Verdict(problem(host).orElse(null));
  }

  @Override
  public Optional<String> problem(String rawHost) {
    String host = rawHost == null ? "" : rawHost.trim().toLowerCase(Locale.ROOT);
    if (host.startsWith("[") && host.endsWith("]")) {
      host = host.substring(1, host.length() - 1);
    }
    if (host.isEmpty()) {
      return Optional.of("The address must name a host.");
    }
    if (internalName(host)) {
      return Optional.of("The address points to an internal host name.");
    }
    InetAddress[] addresses;
    try {
      addresses = resolver.resolve(host);
    } catch (UnknownHostException unknown) {
      return Optional.of("The host " + host + " could not be found.");
    }
    if (addresses == null || addresses.length == 0) {
      return Optional.of("The host " + host + " could not be found.");
    }
    for (InetAddress address : addresses) {
      if (!isPublic(address)) {
        return Optional.of("The address points to a private or local network address.");
      }
    }
    return Optional.empty();
  }

  private static boolean internalName(String host) {
    String name = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
    return name.equals("localhost")
        || name.endsWith(".localhost")
        || name.endsWith(".local")
        || name.endsWith(".internal")
        || name.endsWith(".localdomain")
        || !name.contains(".") && !name.contains(":");
  }

  static boolean isPublic(InetAddress address) {
    if (address.isAnyLocalAddress()
        || address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isSiteLocalAddress()
        || address.isMulticastAddress()) {
      return false;
    }
    byte[] b = address.getAddress();
    if (address instanceof Inet4Address) {
      int first = b[0] & 0xff;
      int second = b[1] & 0xff;
      return first != 0 // 0.0.0.0/8
          && !(first == 100 && second >= 64 && second <= 127) // carrier-grade NAT 100.64/10
          && !(first == 192 && second == 0 && (b[2] & 0xff) == 0) // 192.0.0.0/24
          && !(first == 198 && (second == 18 || second == 19)) // benchmarking 198.18/15
          && first < 224; // multicast and reserved
    }
    if (address instanceof Inet6Address) {
      int first = b[0] & 0xff;
      if ((first & 0xfe) == 0xfc) {
        return false; // unique local fc00::/7
      }
      boolean v4Mapped = true;
      for (int i = 0; i < 10; i++) {
        v4Mapped &= b[i] == 0;
      }
      if (v4Mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
        try {
          return isPublic(InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]}));
        } catch (UnknownHostException impossible) {
          return false;
        }
      }
    }
    return true;
  }
}
