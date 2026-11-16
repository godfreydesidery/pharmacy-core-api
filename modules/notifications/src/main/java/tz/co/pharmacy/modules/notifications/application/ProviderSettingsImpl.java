package tz.co.pharmacy.modules.notifications.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.pharmacy.modules.notifications.domain.Message;
import tz.co.pharmacy.modules.notifications.domain.MessageStatus;
import tz.co.pharmacy.modules.notifications.domain.ProviderSetting;
import tz.co.pharmacy.modules.notifications.persistence.MessageRepository;
import tz.co.pharmacy.modules.notifications.persistence.ProviderSettingRepository;
import tz.co.pharmacy.modules.notifications.ports.inbound.Channel;
import tz.co.pharmacy.modules.notifications.ports.inbound.ProviderSettings;
import tz.co.pharmacy.modules.notifications.providers.ProviderCatalog;
import tz.co.pharmacy.modules.notifications.providers.ProviderCatalog.Definition;
import tz.co.pharmacy.modules.notifications.providers.ProviderCatalog.Field;
import tz.co.pharmacy.modules.notifications.providers.ProviderRouter;
import tz.co.pharmacy.modules.notifications.providers.ProviderRouter.Route;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.persistence.TenantContext;

@Service
class ProviderSettingsImpl implements ProviderSettings {

  /** The channels a provider sends; IN_APP needs none. */
  static final List<Channel> CHANNELS = List.of(Channel.EMAIL, Channel.SMS, Channel.WHATSAPP);

  private final ProviderSettingRepository settings;
  private final MessageRepository messages;
  private final ProviderRouter router;
  private final TransactionTemplate transactions;
  private final String defaultCountryCode;

  ProviderSettingsImpl(
      ProviderSettingRepository settings,
      MessageRepository messages,
      ProviderRouter router,
      PlatformTransactionManager transactionManager,
      @Value("${pharmacy.notifications.default-country-code:255}") String defaultCountryCode) {
    this.settings = settings;
    this.messages = messages;
    this.router = router;
    this.transactions = new TransactionTemplate(transactionManager);
    this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.defaultCountryCode = defaultCountryCode;
  }

  @Override
  public List<ProviderSettingView> platform() {
    return asPlatform(
        () ->
            transactions.execute(
                status -> {
                  List<ProviderSettingView> out = new ArrayList<>();
                  for (Channel channel : CHANNELS) {
                    Optional<ProviderSetting> row =
                        settings.findByTenantUidIsNullAndChannel(channel);
                    out.add(view(channel, row, router.routePlatform(channel), "OWN"));
                  }
                  return out;
                }));
  }

  @Override
  public ProviderSettingView savePlatform(Channel channel, ProviderSettingCommand command) {
    Channel ch = sending(channel);
    asPlatform(
        () ->
            transactions.execute(
                status -> {
                  ProviderSetting row =
                      settings
                          .findByTenantUidIsNullAndChannel(ch)
                          .orElseGet(() -> new ProviderSetting(ch));
                  apply(row, ProviderSetting.OWN, command);
                  settings.saveAndFlush(row);
                  return null;
                }));
    // Read back after the commit, so the route reflects the saved row.
    return platform().stream().filter(v -> v.channel() == ch).findFirst().orElseThrow();
  }

  @Override
  public TestResult testPlatform(Channel channel, String to) {
    Channel ch = sending(channel);
    return asPlatform(() -> test(ch, to, router.routePlatform(ch)));
  }

  @Override
  public List<ProviderSettingView> tenant() {
    UUID tenant = TenantContext.requireTenant();
    return transactions.execute(
        status -> {
          List<ProviderSettingView> out = new ArrayList<>();
          for (Channel channel : CHANNELS) {
            out.add(
                view(
                    channel,
                    settings.findByTenantUidAndChannel(tenant, channel),
                    router.route(channel),
                    ProviderSetting.PLATFORM));
          }
          return out;
        });
  }

  @Override
  public ProviderSettingView saveTenant(Channel channel, ProviderSettingCommand command) {
    Channel ch = sending(channel);
    UUID tenant = TenantContext.requireTenant();
    String mode =
        command == null || command.mode() == null
            ? ProviderSetting.OWN
            : command.mode().trim().toUpperCase();
    if (!ProviderSetting.OWN.equals(mode) && !ProviderSetting.PLATFORM.equals(mode)) {
      throw Refusal.invalid("invalid_mode", "mode must be PLATFORM or OWN.", "mode");
    }
    ProviderSettingView view =
        transactions.execute(
            status -> {
              ProviderSetting row =
                  settings
                      .findByTenantUidAndChannel(tenant, ch)
                      .orElseGet(() -> new ProviderSetting(ch));
              if (ProviderSetting.PLATFORM.equals(mode)) {
                // Keep the pharmacy's own details for later; just stop using them.
                row.change(mode, row.getProvider(), row.getSettings(), row.getSecrets());
              } else {
                apply(row, mode, command);
              }
              settings.saveAndFlush(row);
              return null;
            });
    return tenant().stream().filter(v -> v.channel() == ch).findFirst().orElse(view);
  }

  @Override
  public TestResult testTenant(Channel channel, String to) {
    Channel ch = sending(channel);
    TenantContext.requireTenant();
    return test(ch, to, router.route(ch));
  }

  /** Validates and applies a command to a row: the provider, its plain fields, its secrets. */
  private void apply(ProviderSetting row, String mode, ProviderSettingCommand command) {
    if (command == null) {
      throw Refusal.malformed("The request needs a body.");
    }
    String code = Checks.text(command.provider(), "provider", 40).toLowerCase();
    Definition definition =
        ProviderCatalog.find(row.getChannel(), code)
            .orElseThrow(
                () ->
                    Refusal.invalid(
                        "unknown_provider",
                        code + " cannot send " + row.getChannel().name().toLowerCase() + ".",
                        "provider"));
    Map<String, String> given = command.settings() == null ? Map.of() : command.settings();
    Map<String, String> plain = new LinkedHashMap<>();
    for (Field field : definition.plain()) {
      String value = given.get(field.key());
      if (value != null && !value.isBlank()) {
        plain.put(field.key(), value.trim());
      }
    }
    if (plain.containsKey("port") && !plain.get("port").matches("\\d{1,5}")) {
      throw Refusal.invalid("invalid_port", "port must be a number.", "settings.port");
    }
    // Secrets: keep the stored ones of the same provider, then apply the ones given.
    Map<String, String> secrets = new LinkedHashMap<>();
    if (code.equals(row.getProvider()) && row.getSecrets() != null) {
      secrets.putAll(router.secrets(row));
    }
    Map<String, String> newSecrets = command.secrets() == null ? Map.of() : command.secrets();
    for (Field field : definition.secrets()) {
      if (newSecrets.containsKey(field.key())) {
        String value = newSecrets.get(field.key());
        if (value == null || value.isBlank()) {
          secrets.remove(field.key());
        } else {
          secrets.put(field.key(), value.trim());
        }
      }
    }
    secrets.keySet().retainAll(definition.secrets().stream().map(Field::key).toList());
    for (Field field : definition.fields()) {
      boolean present =
          field.secret() ? secrets.containsKey(field.key()) : plain.containsKey(field.key());
      if (field.required() && !present) {
        throw Refusal.invalid(
            "provider_incomplete",
            code + " needs " + field.key() + ".",
            (field.secret() ? "secrets." : "settings.") + field.key());
      }
    }
    row.change(mode, code, ProviderRouter.write(plain), router.sealSecrets(secrets));
  }

  private TestResult test(Channel channel, String to, Route route) {
    String address = address(channel, to);
    String subject = "Pharmacy Core test message / Ujumbe wa majaribio";
    String text =
        "This is a test message from Pharmacy Core through "
            + route.provider()
            + ". / Huu ni ujumbe wa majaribio kutoka Pharmacy Core.";
    String error = null;
    try {
      route.sender().send(address, subject, "<p>" + text + "</p>", text);
    } catch (RuntimeException failure) {
      error =
          failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }
    String outcome = error;
    transactions.executeWithoutResult(
        status -> {
          Message message =
              new Message(
                  "provider.test",
                  "system",
                  channel,
                  null,
                  null,
                  address,
                  "en",
                  channel == Channel.EMAIL ? subject : null,
                  text,
                  channel == Channel.EMAIL ? "<p>" + text + "</p>" : null,
                  null,
                  null);
          if (outcome == null) {
            message.sent(route.label());
          } else {
            message.failed(route.label(), outcome, null);
          }
          messages.save(message);
        });
    return new TestResult(error == null, route.provider(), route.source(), error);
  }

  private String address(Channel channel, String to) {
    String raw = Checks.text(to, "to", 200);
    if (channel == Channel.EMAIL) {
      if (!raw.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
        throw Refusal.invalid("invalid_email", "Enter a valid email address.", "to");
      }
      return raw;
    }
    String phone = Phones.e164(raw, defaultCountryCode);
    if (phone == null) {
      throw Refusal.invalid("invalid_phone", "Enter a phone number such as +255712345678.", "to");
    }
    return phone;
  }

  private ProviderSettingView view(
      Channel channel, Optional<ProviderSetting> row, Route route, String defaultMode) {
    Optional<Definition> definition =
        row.flatMap(r -> ProviderCatalog.find(channel, r.getProvider()));
    Map<String, String> plain = row.map(ProviderRouter::plain).orElse(Map.of());
    Map<String, String> secrets = row.map(router::secrets).orElse(Map.of());
    Map<String, String> masked = new LinkedHashMap<>();
    secrets.forEach((k, v) -> masked.put(k, mask(v)));
    boolean configured =
        definition
            .map(
                d ->
                    d.fields().stream()
                        .filter(Field::required)
                        .allMatch(
                            f ->
                                f.secret()
                                    ? secrets.containsKey(f.key())
                                    : plain.containsKey(f.key())))
            .orElse(row.isEmpty());
    Optional<Message> lastSent =
        messages.findFirstByChannelAndStatusOrderBySentAtDesc(channel, MessageStatus.SENT);
    Optional<Message> lastFailed =
        messages.findFirstByChannelAndStatusOrderByUpdatedAtDesc(channel, MessageStatus.FAILED);
    return new ProviderSettingView(
        channel,
        row.map(ProviderSetting::getMode).orElse(defaultMode),
        row.map(ProviderSetting::getProvider).orElse(route.provider()),
        plain,
        masked,
        configured,
        route.provider(),
        route.source(),
        lastSent.map(Message::getSentAt).orElse(null),
        lastFailed
            .map(m -> m.getUpdatedAt() == null ? m.getCreatedAt() : m.getUpdatedAt())
            .orElse(null),
        lastFailed.map(Message::getLastError).orElse(null),
        ProviderCatalog.of(channel).stream()
            .map(
                d ->
                    new ProviderOption(
                        d.code(),
                        d.fields().stream()
                            .map(f -> new FieldView(f.key(), f.secret(), f.required()))
                            .toList()))
            .toList());
  }

  static String mask(String secret) {
    if (secret == null || secret.length() <= 4) {
      return "••••";
    }
    return "••••" + secret.substring(secret.length() - 4);
  }

  private static Channel sending(Channel channel) {
    Channel ch = Checks.required(channel, "channel");
    if (ch == Channel.IN_APP) {
      throw Refusal.invalid("invalid_channel", "In-app messages need no provider.", "channel");
    }
    return ch;
  }

  /** Runs work in the platform scope (no tenant), where the platform's rows are written. */
  private static <T> T asPlatform(java.util.function.Supplier<T> work) {
    return TenantContext.callAs(null, work);
  }
}
