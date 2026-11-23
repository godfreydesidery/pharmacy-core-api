package tz.co.pharmacy.modules.notifications.providers;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tz.co.pharmacy.modules.notifications.domain.ProviderSetting;
import tz.co.pharmacy.modules.notifications.persistence.ProviderSettingRepository;
import tz.co.pharmacy.modules.notifications.ports.inbound.Channel;
import tz.co.pharmacy.modules.notifications.ports.outbound.EmailSender;
import tz.co.pharmacy.modules.notifications.ports.outbound.SmsSender;
import tz.co.pharmacy.modules.notifications.ports.outbound.WhatsAppSender;
import tz.co.pharmacy.platform.persistence.TenantContext;

/**
 * Picks, per message, the providers a channel sends through, in the order to try them: the
 * pharmacy's own active providers, else the platform's, else the provider the environment
 * configured at start. A provider whose circuit breaker is open ({@link ProviderHealth}) is tried
 * after the healthy ones. Rows are read on every send, so a change applies to the next message.
 */
@Component
public class ProviderRouter {

  private static final Logger LOG = LoggerFactory.getLogger(ProviderRouter.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  public static final String SOURCE_OWN = "OWN";
  public static final String SOURCE_PLATFORM = "PLATFORM";
  public static final String SOURCE_ENVIRONMENT = "ENVIRONMENT";

  /** Sends one rendered message to one address. */
  public interface ChannelSender {
    void send(String to, String subject, String htmlBody, String textBody);
  }

  /**
   * One way a channel's messages can go: the provider row ({@code uid}, null for the
   * environment's), its {@code name}, {@code provider} (such as beem) and {@code source} (OWN,
   * PLATFORM or ENVIRONMENT).
   */
  public record Route(UUID uid, String name, String provider, String source, ChannelSender sender) {

    /**
     * The provider as the delivery log records it: "beem:own", "smtp:platform" or "log"; with the
     * name when it is not just the provider's, so two accounts of one provider can be told apart.
     */
    public String label() {
      String base =
          switch (source) {
            case SOURCE_OWN -> provider + ":own";
            case SOURCE_PLATFORM -> provider + ":platform";
            default -> provider;
          };
      return name == null || name.equalsIgnoreCase(provider) ? base : base + ":" + name;
    }
  }

  private final ProviderSettingRepository settings;
  private final SecretSeal seal;
  private final ProviderHttp http;
  private final EmailSender envEmail;
  private final SmsSender envSms;
  private final WhatsAppSender envWhatsApp;
  private final ProviderHealth health;
  private final LogOutput logOutput;
  private final SmtpRules smtpRules;
  private final TransactionTemplate reads;

  /** Mail senders built from a provider row, by uid and version: a cache of the row, not state. */
  private final Map<String, JavaMailSenderImpl> mailers = new ConcurrentHashMap<>();

  @SuppressWarnings("java:S107")
  ProviderRouter(
      ProviderSettingRepository settings,
      SecretSeal seal,
      ProviderHttp http,
      EmailSender envEmail,
      SmsSender envSms,
      WhatsAppSender envWhatsApp,
      ProviderHealth health,
      LogOutput logOutput,
      SmtpRules smtpRules,
      PlatformTransactionManager transactionManager) {
    this.smtpRules = smtpRules;
    this.settings = settings;
    this.seal = seal;
    this.http = http;
    this.envEmail = envEmail;
    this.envSms = envSms;
    this.envWhatsApp = envWhatsApp;
    this.health = health;
    this.logOutput = logOutput;
    this.reads = new TransactionTemplate(transactionManager);
    this.reads.setReadOnly(true);
    this.reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /**
   * The routes for the current scope, in the order to try them: the tenant's own active providers,
   * else the platform's, else the environment's; healthy ones before tripped ones.
   */
  public List<Route> routes(Channel channel) {
    Optional<UUID> tenant = TenantContext.tenantUid();
    List<ProviderSetting> rows =
        reads.execute(
            status -> {
              if (tenant.isPresent()) {
                List<ProviderSetting> own =
                    active(
                        settings.findByTenantUidAndChannelOrderByPriorityAscIdAsc(
                            tenant.get(), channel));
                if (!own.isEmpty()) {
                  return own;
                }
              }
              return active(
                  settings.findByTenantUidIsNullAndChannelOrderByPriorityAscIdAsc(channel));
            });
    return chain(channel, rows);
  }

  /** The first route for the current scope. */
  public Route route(Channel channel) {
    return routes(channel).getFirst();
  }

  /** The platform's routes, ignoring any tenant: its active providers, else the environment's. */
  public List<Route> routesPlatform(Channel channel) {
    List<ProviderSetting> rows =
        reads.execute(
            status ->
                active(settings.findByTenantUidIsNullAndChannelOrderByPriorityAscIdAsc(channel)));
    return chain(channel, rows);
  }

  public Route routePlatform(Channel channel) {
    return routesPlatform(channel).getFirst();
  }

  /** The route of one provider row, whether or not it is active (to test it). */
  public Route routeOf(ProviderSetting row) {
    return configured(row, row.getTenantUid() == null ? SOURCE_PLATFORM : SOURCE_OWN);
  }

  private List<Route> chain(Channel channel, List<ProviderSetting> rows) {
    if (rows == null || rows.isEmpty()) {
      return List.of(environment(channel));
    }
    List<Route> healthy = new java.util.ArrayList<>();
    List<Route> tripped = new java.util.ArrayList<>();
    for (ProviderSetting row : rows) {
      Route route = routeOf(row);
      (health.tripped(row.getUid()) ? tripped : healthy).add(route);
    }
    healthy.addAll(tripped);
    return healthy;
  }

  private static List<ProviderSetting> active(List<ProviderSetting> rows) {
    return rows.stream().filter(ProviderSetting::isActive).toList();
  }

  /** The environment's provider for a channel: the bean chosen by configuration at start. */
  public Route environment(Channel channel) {
    return switch (channel) {
      case EMAIL ->
          new Route(
              null,
              envEmail.name(),
              envEmail.name(),
              SOURCE_ENVIRONMENT,
              (to, subject, html, text) -> envEmail.send(to, subject, html, text));
      case SMS ->
          new Route(
              null,
              envSms.name(),
              envSms.name(),
              SOURCE_ENVIRONMENT,
              (to, s, h, text) -> envSms.send(to, text));
      case WHATSAPP ->
          new Route(
              null,
              envWhatsApp.name(),
              envWhatsApp.name(),
              SOURCE_ENVIRONMENT,
              (to, s, h, text) -> envWhatsApp.send(to, text));
      case IN_APP ->
          new Route(null, "in-app", "in-app", SOURCE_ENVIRONMENT, (to, s, h, text) -> {});
    };
  }

  /** The plain settings of a row, as a map. */
  public static Map<String, String> plain(ProviderSetting row) {
    return read(row.getSettings());
  }

  /**
   * The secrets of a row, opened. Refuses with a {@link ProviderException} when they were sealed
   * under another {@code pharmacy.access.encryption-key}: they must be entered again.
   */
  public Map<String, String> secrets(ProviderSetting row) {
    if (row.getSecrets() == null || row.getSecrets().isBlank()) {
      return Map.of();
    }
    String opened;
    try {
      opened = seal.open(row.getSecrets());
    } catch (IllegalStateException unreadable) {
      LOG.warn(
          "The saved {} secrets of {} cannot be opened: {}",
          row.getProvider(),
          row.getTenantUid() == null ? "the platform" : "tenant " + row.getTenantUid(),
          unreadable.getMessage());
      throw new ProviderException(
          "The saved "
              + row.getProvider()
              + " password or key can no longer be read (the server's encryption key changed)."
              + " Enter it again in the message provider settings.");
    }
    return read(opened);
  }

  /** Whether a row's secrets can be opened with the current key (true when it has none). */
  public boolean secretsReadable(ProviderSetting row) {
    try {
      secrets(row);
      return true;
    } catch (ProviderException unreadable) {
      return false;
    }
  }

  public String sealSecrets(Map<String, String> secrets) {
    return secrets.isEmpty() ? null : seal.seal(write(secrets));
  }

  public static String write(Map<String, String> values) {
    return JSON.writeValueAsString(values);
  }

  @SuppressWarnings("unchecked")
  static Map<String, String> read(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    Map<String, Object> raw = JSON.readValue(json, Map.class);
    Map<String, String> out = new LinkedHashMap<>();
    raw.forEach((k, v) -> out.put(k, v == null ? null : String.valueOf(v)));
    return out;
  }

  private Route configured(ProviderSetting row, String source) {
    Map<String, String> plain = plain(row);
    String code = row.getProvider();
    Map<String, String> secret;
    try {
      secret = secrets(row);
    } catch (ProviderException unreadable) {
      // Still a route, so settings pages load; each send fails with the reason.
      return new Route(
          row.getUid(),
          row.getName(),
          code,
          source,
          (to, s, h, text) -> {
            throw unreadable;
          });
    }
    ChannelSender sender =
        switch (row.getChannel()) {
          case EMAIL -> "smtp".equals(code) ? smtp(row, plain, secret) : logSender("email", source);
          case SMS ->
              switch (code) {
                case "africastalking" ->
                    (to, s, h, text) ->
                        http.send(
                            code,
                            AfricasTalkingSmsSender.request(
                                require(plain, "username", code),
                                require(secret, "apiKey", code),
                                plain.get("senderId"),
                                to,
                                text));
                case "twilio" ->
                    (to, s, h, text) ->
                        http.send(
                            code,
                            TwilioMessages.request(
                                require(plain, "accountSid", code),
                                require(secret, "authToken", code),
                                require(plain, "from", code),
                                to,
                                text,
                                false));
                case "beem" ->
                    (to, s, h, text) ->
                        http.send(
                            code,
                            BeemSmsSender.request(
                                require(secret, "apiKey", code),
                                require(secret, "secretKey", code),
                                require(plain, "senderId", code),
                                to,
                                text));
                default -> logSender("sms", source);
              };
          case WHATSAPP ->
              "twilio".equals(code)
                  ? (to, s, h, text) ->
                      http.send(
                          "twilio-whatsapp",
                          TwilioMessages.request(
                              require(plain, "accountSid", code),
                              require(secret, "authToken", code),
                              require(plain, "from", code),
                              to,
                              text,
                              true))
                  : logSender("whatsapp", source);
          case IN_APP -> (to, s, h, text) -> {};
        };
    return new Route(row.getUid(), row.getName(), code, source, sender);
  }

  private ChannelSender logSender(String channel, String source) {
    return (to, subject, html, text) ->
        logOutput.write(channel + ":" + source.toLowerCase(), to, subject, text);
  }

  private ChannelSender smtp(
      ProviderSetting row, Map<String, String> plain, Map<String, String> secret) {
    String from = require(plain, "from", "smtp");
    String host = SmtpRules.normalHost(require(plain, "host", "smtp"));
    int port = parsePort(plain.get("port"));
    JavaMailSenderImpl mailer =
        mailers.computeIfAbsent(
            row.getUid() + ":" + row.getVersion(),
            key -> {
              JavaMailSenderImpl m = new JavaMailSenderImpl();
              m.setHost(host);
              m.setPort(port);
              String user = plain.get("username");
              boolean authenticate = user != null && !user.isBlank();
              if (authenticate) {
                m.setUsername(user);
                m.setPassword(secret.get("password"));
              }
              // Always encrypted (INT-2): implicit TLS on 465, STARTTLS required elsewhere. A
              // "tls": "false" saved before is ignored.
              m.getJavaMailProperties().putAll(SmtpRules.connection(port, authenticate));
              return m;
            });
    return (to, subject, html, text) -> {
      // Checked before every send, a test too: the name may now resolve to a private address,
      // and a provider saved before the rules existed must not reach one either.
      Optional<SmtpRules.Refused> refused = smtpRules.check(host, port);
      if (refused.isPresent()) {
        throw new ProviderException(
            refused.get().reason() + ": " + refused.get().problem() + " Change the provider.");
      }
      try {
        MimeMessage message = mailer.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(from);
        helper.setTo(to);
        helper.setSubject(subject == null ? "" : subject);
        helper.setText(text == null ? "" : text, html == null ? text : html);
        mailer.send(message);
      } catch (MessagingException | MailException exception) {
        throw new ProviderException(
            "smtp refused the message: " + exception.getMessage(), exception);
      }
    };
  }

  private static int parsePort(String port) {
    try {
      return port == null || port.isBlank() ? 587 : Integer.parseInt(port.trim());
    } catch (NumberFormatException bad) {
      throw new ProviderException("smtp port is not a number: " + port);
    }
  }

  private static String require(Map<String, String> values, String key, String provider) {
    String value = values.get(key);
    if (value == null || value.isBlank()) {
      throw new ProviderException(provider + " is not configured: set " + key);
    }
    return value;
  }
}
