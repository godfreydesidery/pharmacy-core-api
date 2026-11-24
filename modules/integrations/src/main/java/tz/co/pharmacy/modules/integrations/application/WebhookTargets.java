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
 * unspecified and multicast addresses are refused, and so are the IPv6 forms that carry an IPv4
 * address a gateway would reach for us (NAT64 64:ff9b::/96 and 64:ff9b:1::/48, 6to4 2002::/16,
 * Teredo 2001::/32, IPv4-compatible ::/96, discard-only 100::/64, documentation 2001:db8::/32),
 * adversarial review INT-14. They are refused as are localhost and *.local, *.internal and
 * *.localhost names. Hosts listed in {@code pharmacy.integrations.webhooks.allowed-hosts} (empty by
 * default; for local development only) skip these checks and may use plain http.
 *
 * <p>A delivery connects to the very addresses that were checked ({@link #target}), never resolving
 * the name a second time: a name re-pointed between the check and the connection (DNS rebinding)
 * cannot send a delivery to a private address.
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

  /**
   * The URL, the problem with it (null when it may be called) and the addresses its host resolved
   * to when checked: a delivery connects to these, and only these.
   */
  record Target(String problem, URI uri, List<InetAddress> addresses) {
    boolean allowed() {
      return problem == null;
    }
  }

  Verdict check(String url) {
    return new Verdict(target(url).problem());
  }

  /** Checks {@code url} as {@link #check} does, keeping the addresses that passed. */
  Target target(String url) {
    URI uri;
    try {
      uri = URI.create(url.trim());
    } catch (IllegalArgumentException notAUri) {
      return refused(null, "The address is not a valid URL.");
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    if (host.isEmpty() || uri.getUserInfo() != null) {
      return refused(uri, "The address must name a host, without a user name or password.");
    }
    if (allowedHosts.contains(host)) {
      if (!scheme.equals("https") && !scheme.equals("http")) {
        return refused(uri, "The address must be an http or https URL.");
      }
      // Allowed (local development): no address rules, but still pinned to what was resolved.
      Resolved resolved = resolve(bare(host));
      return new Target(resolved.problem(), uri, resolved.addresses());
    }
    if (!scheme.equals("https")) {
      return refused(uri, "The address must be an https URL.");
    }
    Resolved resolved = checked(host);
    return new Target(resolved.problem(), uri, resolved.addresses());
  }

  @Override
  public Optional<String> problem(String rawHost) {
    return Optional.ofNullable(checked(rawHost).problem());
  }

  /** The host's addresses, all public, or the problem with it. */
  private Resolved checked(String rawHost) {
    String host = bare(rawHost == null ? "" : rawHost.trim().toLowerCase(Locale.ROOT));
    if (host.isEmpty()) {
      return new Resolved("The address must name a host.", List.of());
    }
    if (internalName(host)) {
      return new Resolved("The address points to an internal host name.", List.of());
    }
    Resolved resolved = resolve(host);
    if (!resolved.addresses().isEmpty()) {
      for (InetAddress address : resolved.addresses()) {
        if (!isPublic(address)) {
          return new Resolved(
              "The address points to a private or local network address.", List.of());
        }
      }
    }
    return resolved;
  }

  private Resolved resolve(String host) {
    InetAddress[] addresses;
    try {
      addresses = resolver.resolve(host);
    } catch (UnknownHostException unknown) {
      return new Resolved("The host " + host + " could not be found.", List.of());
    }
    if (addresses == null || addresses.length == 0) {
      return new Resolved("The host " + host + " could not be found.", List.of());
    }
    return new Resolved(null, List.of(addresses));
  }

  private static Target refused(URI uri, String problem) {
    return new Target(problem, uri, List.of());
  }

  /** {@code [::1]} as {@code ::1}. */
  private static String bare(String host) {
    return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
  }

  private record Resolved(String problem, List<InetAddress> addresses) {}

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
      int second = b[1] & 0xff;
      int word0 = (first << 8) | second;
      int word1 = ((b[2] & 0xff) << 8) | (b[3] & 0xff);
      if (word0 == 0x2002 // 6to4 2002::/16: the IPv4 address in bits 16-47 is what a relay reaches
          || (word0 == 0x2001 && word1 == 0x0000) // Teredo 2001::/32
          || (word0 == 0x2001 && word1 == 0x0db8) // documentation 2001:db8::/32
          || (word0 == 0x0064 && word1 == 0xff9b) // NAT64 64:ff9b::/96 and 64:ff9b:1::/48
          || (word0 == 0x0100 && zero(b, 2, 8))) { // discard-only 100::/64
        return false;
      }
      if (zero(b, 0, 12)) {
        return false; // IPv4-compatible ::/96 (deprecated), :: and ::1 among them
      }
      boolean v4Mapped = zero(b, 0, 10);
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

  /** Whether bytes {@code from} (inclusive) to {@code to} (exclusive) are all zero. */
  private static boolean zero(byte[] bytes, int from, int to) {
    for (int i = from; i < to; i++) {
      if (bytes[i] != 0) {
        return false;
      }
    }
    return true;
  }
}
