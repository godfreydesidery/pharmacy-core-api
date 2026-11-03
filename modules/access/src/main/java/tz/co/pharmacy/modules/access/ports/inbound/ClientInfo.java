package tz.co.pharmacy.modules.access.ports.inbound;

/** Where a request comes from: the client's IP address and its user agent, either may be null. */
public record ClientInfo(String ip, String userAgent) {

  public static final ClientInfo UNKNOWN = new ClientInfo(null, null);
}
