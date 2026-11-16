package tz.co.pharmacy.modules.notifications.providers;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
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
 * Picks, per message, the provider a channel sends through: the pharmacy's own account (its row in
 * mode OWN), else the platform's (the row without a tenant), else the provider the environment
 * configured at start. Rows are read on every send, so a change applies to the next message.
 */
@Component
public class ProviderRouter {

  private static final Logger log = LoggerFactory.getLogger("notifications.log-provider");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  public static final String SOURCE_OWN = "OWN";
  public static final String SOURCE_PLATFORM = "PLATFORM";
  public static final String SOURCE_ENVIRONMENT = "ENVIRONMENT";

  /** Sends one rendered message to one address. */
  public interface ChannelSender {
    void send(String to, String subject, String htmlBody, String textBody);
  }

  /**
   * Where a channel's messages go: {@code provider} (such as beem), {@code source} (OWN, PLATFORM
   * or ENVIRONMENT).
   */
  public record Route(String provider, String source, ChannelSender sender) {

    /** The provider as the delivery log records it: "beem:own", "smtp:platform" or "log". */
    public String label() {
      return switch (source) {
        case SOURCE_OWN -> provider + ":own";
        case SOURCE_PLATFORM -> provider + ":platform";
        default -> provider;
      };
    }
  }

  private final ProviderSettingRepository settings;
  private final SecretSeal seal;
  private final ProviderHttp http;
  private final EmailSender envEmail;
  private final SmsSender envSms;
  private final WhatsAppSender envWhatsApp;
  private final TransactionTemplate reads;
  private final Map<String, JavaMailSenderImpl> mailers = new ConcurrentHashMap<>();

  ProviderRouter(
      ProviderSettingRepository settings,
      SecretSeal seal,
      ProviderHttp http,
      EmailSender envEmail,
      SmsSender envSms,
      WhatsAppSender envWhatsApp,
      PlatformTransactionManager transactionManager) {
    this.settings = settings;
    this.seal = seal;
    this.http = http;
    this.envEmail = envEmail;
    this.envSms = envSms;
    this.envWhatsApp = envWhatsApp;
    this.reads = new TransactionTemplate(transactionManager);
    this.reads.setReadOnly(true);
    this.reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /**
   * The route for the current scope: the tenant's own account, the platform's, the environment's.
   */
  public Route route(Channel channel) {
    Optional<UUID> tenant = TenantContext.tenantUid();
    Optional<ProviderSetting> chosen =
        reads.execute(
            status -> {
              if (tenant.isPresent()) {
                Optional<ProviderSetting> own =
                    settings
                        .findByTenantUidAndChannel(tenant.get(), channel)
                        .filter(ProviderSetting::own);
                if (own.isPresent()) {
                  return own;
                }
              }
              return settings.findByTenantUidIsNullAndChannel(channel);
            });
    if (chosen != null && chosen.isPresent()) {
      ProviderSetting row = chosen.get();
      return configured(row, row.getTenantUid() == null ? SOURCE_PLATFORM : SOURCE_OWN);
    }
    return environment(channel);
  }

  /** The platform's route, ignoring any tenant: its row, else the environment's provider. */
  public Route routePlatform(Channel channel) {
    Optional<ProviderSetting> row =
        reads.execute(status -> settings.findByTenantUidIsNullAndChannel(channel));
    return row != null && row.isPresent()
        ? configured(row.get(), SOURCE_PLATFORM)
        : environment(channel);
  }

  /** The environment's provider for a channel: the bean chosen by configuration at start. */
  public Route environment(Channel channel) {
    return switch (channel) {
      case EMAIL ->
          new Route(
              envEmail.name(),
              SOURCE_ENVIRONMENT,
              (to, subject, html, text) -> envEmail.send(to, subject, html, text));
      case SMS ->
          new Route(envSms.name(), SOURCE_ENVIRONMENT, (to, s, h, text) -> envSms.send(to, text));
      case WHATSAPP ->
          new Route(
              envWhatsApp.name(),
              SOURCE_ENVIRONMENT,
              (to, s, h, text) -> envWhatsApp.send(to, text));
      case IN_APP -> new Route("in-app", SOURCE_ENVIRONMENT, (to, s, h, text) -> {});
    };
  }

  /** The plain settings of a row, as a map. */
  public static Map<String, String> plain(ProviderSetting row) {
    return read(row.getSettings());
  }

  /** The secrets of a row, opened. */
  public Map<String, String> secrets(ProviderSetting row) {
    return row.getSecrets() == null || row.getSecrets().isBlank()
        ? Map.of()
        : read(seal.open(row.getSecrets()));
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
    Map<String, String> secret = secrets(row);
    String code = row.getProvider();
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
    return new Route(code, source, sender);
  }

  private static ChannelSender logSender(String channel, String source) {
    return (to, subject, html, text) ->
        log.info(
            "[{}:{}] to={}{}\n{}",
            channel,
            source.toLowerCase(),
            to,
            subject == null ? "" : " subject=\"" + subject + "\"",
            text);
  }

  private ChannelSender smtp(
      ProviderSetting row, Map<String, String> plain, Map<String, String> secret) {
    String from = require(plain, "from", "smtp");
    JavaMailSenderImpl mailer =
        mailers.computeIfAbsent(
            row.getUid() + ":" + row.getVersion(),
            key -> {
              JavaMailSenderImpl m = new JavaMailSenderImpl();
              m.setHost(require(plain, "host", "smtp"));
              m.setPort(parsePort(plain.get("port")));
              String user = plain.get("username");
              if (user != null && !user.isBlank()) {
                m.setUsername(user);
                m.setPassword(secret.get("password"));
              }
              Properties props = m.getJavaMailProperties();
              props.put("mail.smtp.auth", String.valueOf(user != null && !user.isBlank()));
              props.put(
                  "mail.smtp.starttls.enable",
                  String.valueOf(!"false".equalsIgnoreCase(plain.get("tls"))));
              props.put("mail.smtp.connectiontimeout", "10000");
              props.put("mail.smtp.timeout", "10000");
              props.put("mail.smtp.writetimeout", "10000");
              return m;
            });
    return (to, subject, html, text) -> {
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
