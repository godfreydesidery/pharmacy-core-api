package tz.co.pharmacy.delivery.edge;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * The IP address of the person behind a request, for sign-in records and the sign-in rate limit.
 *
 * <p>In production the API sits behind proxies: the hosting edge, then the web app, which passes
 * {@code /api} on. Their connection comes from a private address, so the client is read from the
 * headers the edge sets, and only then: a connection from a public address is taken as it is.
 *
 * <ol>
 *   <li>{@code X-Real-IP}: the hosting edge (Railway) writes the address that connected to it,
 *       replacing any copy the client sent. Its {@code X-Forwarded-For} may also hold the edge's
 *       own hops, in no promised order, so X-Real-IP comes first.
 *   <li>Otherwise {@code X-Forwarded-For}, read from the right (each proxy appends): the first
 *       public address is the one our edge saw; anything a client wrote sits further left.
 * </ol>
 */
final class ClientAddress {

  static final String REAL_IP = "X-Real-IP";
  static final String FORWARDED_FOR = "X-Forwarded-For";

  private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
  private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

  private ClientAddress() {}

  static String of(HttpServletRequest request) {
    String peer = request.getRemoteAddr();
    if (!isPrivate(peer)) {
      return peer;
    }
    String real = request.getHeader(REAL_IP);
    if (real != null && literal(real) != null) {
      return literal(real);
    }
    String forwarded = request.getHeader(FORWARDED_FOR);
    if (forwarded == null || forwarded.isBlank()) {
      return peer;
    }
    String[] hops = forwarded.split(",");
    for (int i = hops.length - 1; i >= 0; i--) {
      String hop = literal(hops[i]);
      if (hop == null) {
        // Not an address: the chain cannot be followed further.
        return peer;
      }
      if (!isPrivate(hop)) {
        return hop;
      }
    }
    // Every hop is private (a request from inside the network): the original sender.
    String first = literal(hops[0]);
    return first == null ? peer : first;
  }

  /** True for loopback, private (RFC 1918, IPv6 unique local), link-local and shared addresses. */
  static boolean isPrivate(String address) {
    InetAddress parsed = parse(address);
    if (parsed == null) {
      return false;
    }
    if (parsed.isLoopbackAddress()
        || parsed.isSiteLocalAddress()
        || parsed.isLinkLocalAddress()
        || parsed.isAnyLocalAddress()) {
      return true;
    }
    byte[] bytes = parsed.getAddress();
    if (bytes.length == 16) {
      return (bytes[0] & 0xfe) == 0xfc; // fc00::/7, unique local (private networks)
    }
    return (bytes[0] & 0xff) == 100 && (bytes[1] & 0xc0) == 64; // 100.64.0.0/10, shared
  }

  /** The address in one hop of the header, or null when it is not an IP address. */
  private static String literal(String hop) {
    String value = hop.trim();
    if (value.startsWith("[") && value.contains("]")) {
      value = value.substring(1, value.indexOf(']')); // [v6]:port
    } else if (value.indexOf(':') > 0 && value.indexOf(':') == value.lastIndexOf(':')) {
      value = value.substring(0, value.indexOf(':')); // v4:port
    }
    return parse(value) == null ? null : value;
  }

  /** Parses an IP literal only: never a host name, so no DNS lookup is made. */
  private static InetAddress parse(String address) {
    if (address == null) {
      return null;
    }
    String value = address.trim();
    boolean literal =
        IPV4.matcher(value).matches()
            ? Arrays.stream(value.split("\\.")).allMatch(o -> Integer.parseInt(o) <= 255)
            : value.contains(":") && IPV6.matcher(value).matches();
    if (!literal) {
      return null;
    }
    try {
      return InetAddress.getByName(value);
    } catch (UnknownHostException | IllegalArgumentException notAnAddress) {
      return null;
    }
  }
}
