package tz.co.pharmacy.modules.notifications.providers;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.notifications.ports.outbound.PublicHosts;

/**
 * Where a pharmacy's (or the platform's) own SMTP server may be (adversarial review INT-2). A
 * pharmacy admin types the host, so the server must never be made to reach its own private network
 * (SSRF) nor be used as a port scanner through "Send test":
 *
 * <ul>
 *   <li>the host must be a public name or address, every address it resolves to public: the webhook
 *       targets' rules ({@link PublicHosts}), checked when the provider is saved and again before
 *       every send (a name can be re-pointed later);
 *   <li>the port must be one mail is submitted on: 25, 465, 587, 2525 or 2587;
 *   <li>the connection is always encrypted: implicit TLS on 465, STARTTLS required on every other
 *       port, with the server's certificate and name checked, so no password ever crosses in plain
 *       text.
 * </ul>
 *
 * <p>Hosts listed in {@code pharmacy.notifications.smtp.allowed-hosts} (empty by default; for local
 * development and the test suite only) skip the host and port rules, never the TLS one.
 */
@Component
public class SmtpRules {

  public static final Set<Integer> PORTS = Set.of(25, 465, 587, 2525, 2587);

  /** The port of implicit TLS (SMTPS); every other port must upgrade with STARTTLS. */
  public static final int IMPLICIT_TLS_PORT = 465;

  /** Why a host or port was refused: a stable reason and the words for the admin. */
  public record Refused(String reason, String field, String problem) {}

  private final PublicHosts hosts;
  private final Set<String> allowedHosts;

  SmtpRules(
      PublicHosts hosts,
      @Value("${pharmacy.notifications.smtp.allowed-hosts:}") List<String> allowedHosts) {
    this.hosts = hosts;
    this.allowedHosts =
        allowedHosts == null
            ? Set.of()
            : allowedHosts.stream()
                .map(host -> host.trim().toLowerCase(Locale.ROOT))
                .filter(host -> !host.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
  }

  /** The host as stored: trimmed, lower case. */
  public static String normalHost(String host) {
    return host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
  }

  /** Why the server may not send through {@code host}:{@code port}, or empty when it may. */
  public Optional<Refused> check(String rawHost, int port) {
    String host = normalHost(rawHost);
    if (!host.matches("[a-z0-9.-]+|\\[?[0-9a-f:.]+]?")) {
      return Optional.of(
          new Refused(
              "smtp_host_not_allowed",
              "settings.host",
              "Enter the mail server's name only, such as smtp.example.com (no scheme, port or"
                  + " path)."));
    }
    if (allowedHosts.contains(host)) {
      return Optional.empty();
    }
    if (!PORTS.contains(port)) {
      return Optional.of(
          new Refused(
              "smtp_port_not_allowed",
              "settings.port",
              "Mail is sent on port 587 (STARTTLS) or 465 (TLS); 25, 2525 and 2587 are also"
                  + " accepted. Port "
                  + port
                  + " is not."));
    }
    return hosts
        .problem(host)
        .map(problem -> new Refused("smtp_host_not_allowed", "settings.host", problem));
  }

  /**
   * The JavaMail properties of a connection: implicit TLS on 465, STARTTLS required elsewhere, the
   * server's identity checked, and 10 second time limits.
   */
  public static Properties connection(int port, boolean authenticate) {
    Properties props = new Properties();
    props.put("mail.smtp.auth", String.valueOf(authenticate));
    if (port == IMPLICIT_TLS_PORT) {
      props.put("mail.smtp.ssl.enable", "true");
    } else {
      props.put("mail.smtp.starttls.enable", "true");
      props.put("mail.smtp.starttls.required", "true");
    }
    props.put("mail.smtp.ssl.checkserveridentity", "true");
    props.put("mail.smtp.connectiontimeout", "10000");
    props.put("mail.smtp.timeout", "10000");
    props.put("mail.smtp.writetimeout", "10000");
    return props;
  }
}
