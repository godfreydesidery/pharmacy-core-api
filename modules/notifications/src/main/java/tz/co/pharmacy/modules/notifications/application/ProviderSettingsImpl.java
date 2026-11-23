package tz.co.pharmacy.modules.notifications.application;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
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
import tz.co.pharmacy.modules.notifications.providers.ProviderHealth;
import tz.co.pharmacy.modules.notifications.providers.ProviderRouter;
import tz.co.pharmacy.modules.notifications.providers.ProviderRouter.Route;
import tz.co.pharmacy.modules.notifications.providers.SmtpRules;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.persistence.TenantContext;

@Service
class ProviderSettingsImpl implements ProviderSettings {

  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(ProviderSettingsImpl.class);

  /** The channels a provider sends; IN_APP needs none. */
  static final List<Channel> CHANNELS = List.of(Channel.EMAIL, Channel.SMS, Channel.WHATSAPP);

  /** The most providers one list may hold. */
  static final int MAX_PER_CHANNEL = 10;

  private final ProviderSettingRepository settings;
  private final MessageRepository messages;
  private final ProviderRouter router;
  private final ProviderHealth health;
  private final TransactionTemplate transactions;
  private final String defaultCountryCode;
  private final SmtpRules smtpRules;
  private final SmsCap smsCap;

  @SuppressWarnings("java:S107")
  ProviderSettingsImpl(
      ProviderSettingRepository settings,
      MessageRepository messages,
      ProviderRouter router,
      ProviderHealth health,
      SmtpRules smtpRules,
      SmsCap smsCap,
      PlatformTransactionManager transactionManager,
      @Value("${pharmacy.notifications.default-country-code:255}") String defaultCountryCode) {
    this.smtpRules = smtpRules;
    this.smsCap = smsCap;
    this.settings = settings;
    this.messages = messages;
    this.router = router;
    this.health = health;
    this.transactions = new TransactionTemplate(transactionManager);
    this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.defaultCountryCode = defaultCountryCode;
  }

  // ---------------------------------------------------------------- platform

  @Override
  public List<ChannelView> platform() {
    return asPlatform(
        () ->
            transactions.execute(
                status -> {
                  List<ChannelView> out = new ArrayList<>();
                  for (Channel channel : CHANNELS) {
                    out.add(
                        channelView(
                            channel,
                            platformRows(channel),
                            ProviderSetting.OWN,
                            router.routesPlatform(channel)));
                  }
                  return out;
                }));
  }

  @Override
  public ProviderView addPlatform(Channel channel, ProviderCommand command) {
    Channel ch = sending(channel);
    return asPlatform(() -> add(ch, command, platformRows(ch).size()));
  }

  @Override
  public ProviderView changePlatform(UUID providerUid, ProviderCommand command) {
    return asPlatform(() -> change(platformRow(providerUid), command));
  }

  @Override
  public void removePlatform(UUID providerUid) {
    asPlatform(() -> remove(platformRow(providerUid)));
  }

  @Override
  public ChannelView reorderPlatform(Channel channel, List<UUID> order) {
    Channel ch = sending(channel);
    asPlatform(() -> reorder(ch, order, () -> platformRows(ch)));
    return platform().stream().filter(view -> view.channel() == ch).findFirst().orElseThrow();
  }

  @Override
  public TestResult testPlatform(Channel channel, UUID providerUid, String to) {
    Channel ch = sending(channel);
    return asPlatform(
        () ->
            test(
                ch,
                to,
                providerUid == null
                    ? router.routesPlatform(ch)
                    : List.of(router.routeOf(testable(platformRow(providerUid))))));
  }

  // ------------------------------------------------------------------ tenant

  @Override
  public List<ChannelView> tenant() {
    UUID tenant = TenantContext.requireTenant();
    return transactions.execute(
        status -> {
          List<ChannelView> out = new ArrayList<>();
          for (Channel channel : CHANNELS) {
            List<ProviderSetting> own = tenantRows(tenant, channel);
            boolean usesOwn = own.stream().anyMatch(ProviderSetting::isActive);
            out.add(
                channelView(
                    channel,
                    own,
                    usesOwn ? ProviderSetting.OWN : "PLATFORM",
                    router.routes(channel)));
          }
          return out;
        });
  }

  @Override
  public ProviderView addTenant(Channel channel, ProviderCommand command) {
    Channel ch = sending(channel);
    UUID tenant = TenantContext.requireTenant();
    return add(ch, command, tenantRows(tenant, ch).size());
  }

  @Override
  public ProviderView changeTenant(UUID providerUid, ProviderCommand command) {
    return change(tenantRow(providerUid), command);
  }

  @Override
  public void removeTenant(UUID providerUid) {
    remove(tenantRow(providerUid));
  }

  @Override
  public ChannelView reorderTenant(Channel channel, List<UUID> order) {
    Channel ch = sending(channel);
    UUID tenant = TenantContext.requireTenant();
    reorder(ch, order, () -> tenantRows(tenant, ch));
    return tenant().stream().filter(view -> view.channel() == ch).findFirst().orElseThrow();
  }

  @Override
  public TestResult testTenant(Channel channel, UUID providerUid, String to) {
    Channel ch = sending(channel);
    UUID tenant = TenantContext.requireTenant();
    List<Route> routes =
        providerUid == null
            ? router.routes(ch)
            : List.of(router.routeOf(testable(tenantRow(providerUid))));
    if (ch == Channel.SMS && !ProviderRouter.SOURCE_OWN.equals(routes.getFirst().source())) {
      // A test through the platform's account is an SMS on the platform's account (INT-5).
      smsCap
          .refusal(tenant)
          .ifPresent(
              refusal -> {
                throw Refusal.invalid(SmsCap.REASON, refusal, "channel");
              });
    }
    return test(ch, to, routes);
  }

  // ------------------------------------------------------------------ shared

  private ProviderView add(Channel channel, ProviderCommand command, int existing) {
    if (existing >= MAX_PER_CHANNEL) {
      throw Refusal.invalid(
          "too_many_providers",
          "A channel holds at most " + MAX_PER_CHANNEL + " providers.",
          "provider");
    }
    UUID uid =
        transactions.execute(
            status -> {
              ProviderSetting row = new ProviderSetting(channel, existing + 1);
              apply(row, command);
              row.activate(command.active() == null || command.active());
              return settings.saveAndFlush(row).getUid();
            });
    return view(found(uid));
  }

  private ProviderView change(ProviderSetting found, ProviderCommand command) {
    transactions.executeWithoutResult(
        status -> {
          ProviderSetting row =
              settings.findByUid(found.getUid()).orElseThrow(ProviderSettingsImpl::notFound);
          apply(row, command);
          if (command.active() != null) {
            row.activate(command.active());
          }
          settings.saveAndFlush(row);
        });
    health.succeeded(found.getUid());
    return view(found(found.getUid()));
  }

  private Void remove(ProviderSetting found) {
    transactions.executeWithoutResult(
        status -> settings.findByUid(found.getUid()).ifPresent(settings::delete));
    health.succeeded(found.getUid());
    return null;
  }

  private Void reorder(Channel channel, List<UUID> order, Supplier<List<ProviderSetting>> current) {
    transactions.executeWithoutResult(
        status -> {
          List<ProviderSetting> rows = current.get();
          Set<UUID> known = new HashSet<>();
          rows.forEach(row -> known.add(row.getUid()));
          if (order == null || order.size() != rows.size() || !known.equals(new HashSet<>(order))) {
            throw Refusal.invalid(
                "invalid_order",
                "Name every " + channel.name() + " provider of the list once, in the new order.",
                "order");
          }
          for (ProviderSetting row : rows) {
            row.moveTo(order.indexOf(row.getUid()) + 1);
          }
          settings.saveAllAndFlush(rows);
        });
    return null;
  }

  private List<ProviderSetting> platformRows(Channel channel) {
    return settings.findByTenantUidIsNullAndChannelOrderByPriorityAscIdAsc(channel);
  }

  private List<ProviderSetting> tenantRows(UUID tenant, Channel channel) {
    return settings.findByTenantUidAndChannelOrderByPriorityAscIdAsc(tenant, channel);
  }

  /** One of the platform's providers (a platform session). */
  private ProviderSetting platformRow(UUID uid) {
    return transactions.execute(
        status ->
            settings
                .findByUid(Checks.required(uid, "providerUid"))
                .filter(row -> row.getTenantUid() == null)
                .orElseThrow(ProviderSettingsImpl::notFound));
  }

  /** One of the current pharmacy's own providers: never the platform's. */
  private ProviderSetting tenantRow(UUID uid) {
    UUID tenant = TenantContext.requireTenant();
    return transactions.execute(
        status ->
            settings
                .findByUid(Checks.required(uid, "providerUid"))
                .filter(row -> tenant.equals(row.getTenantUid()))
                .orElseThrow(ProviderSettingsImpl::notFound));
  }

  private ProviderSetting found(UUID uid) {
    return transactions.execute(
        status -> settings.findByUid(uid).orElseThrow(ProviderSettingsImpl::notFound));
  }

  /** Validates and applies a command to a row: its name, provider, plain fields and secrets. */
  private void apply(ProviderSetting row, ProviderCommand command) {
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
    String name =
        command.name() == null || command.name().isBlank()
            ? code
            : Checks.text(command.name(), "name", 60);
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
    boolean smtp = row.getChannel() == Channel.EMAIL && "smtp".equals(code);
    if (smtp) {
      smtpRules(plain);
    }
    // Secrets: keep the stored ones of the same provider, then apply the ones given. Stored ones
    // sealed under another key are dropped, so the required check asks for them again. An SMTP
    // provider pointed at another host or port drops them too (INT-2): a saved password is never
    // sent to a server the admin who typed it did not choose; it is entered again.
    Map<String, String> secrets = new LinkedHashMap<>();
    boolean sameServer = !smtp || sameServer(row, plain);
    if (code.equals(row.getProvider())
        && sameServer
        && row.getSecrets() != null
        && router.secretsReadable(row)) {
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
    row.change(name, code, ProviderRouter.write(plain), router.sealSecrets(secrets));
  }

  /** The SMTP host and port rules (INT-2): 422 smtp_host_not_allowed or smtp_port_not_allowed. */
  private void smtpRules(Map<String, String> plain) {
    String host = plain.get("host");
    String port = plain.get("port");
    if (host == null || port == null) {
      return; // the required-field check names what is missing
    }
    plain.put("host", SmtpRules.normalHost(host));
    smtpRules
        .check(host, Integer.parseInt(port))
        .ifPresent(
            refused -> {
              throw Refusal.invalid(refused.reason(), refused.problem(), refused.field());
            });
  }

  /** Whether an SMTP row keeps its host and port. */
  private static boolean sameServer(ProviderSetting row, Map<String, String> plain) {
    Map<String, String> stored = ProviderRouter.plain(row);
    return SmtpRules.normalHost(stored.get("host")).equals(SmtpRules.normalHost(plain.get("host")))
        && String.valueOf(stored.get("port")).trim().equals(String.valueOf(plain.get("port")));
  }

  /** A single provider tested on its own follows the send rules first: 422 when they refuse. */
  private ProviderSetting testable(ProviderSetting row) {
    if (row.getChannel() != Channel.EMAIL || !"smtp".equals(row.getProvider())) {
      return row;
    }
    Map<String, String> plain = ProviderRouter.plain(row);
    String host = plain.get("host");
    String port = plain.get("port");
    if (host == null || port == null || !port.trim().matches("\\d{1,5}")) {
      return row; // the send itself says what is missing
    }
    smtpRules
        .check(host, Integer.parseInt(port.trim()))
        .ifPresent(
            refused -> {
              throw Refusal.invalid(refused.reason(), refused.problem(), refused.field());
            });
    return row;
  }

  /**
   * Sends a test message through the routes in order, as a message would go, and logs it: the first
   * that accepts it ends the test.
   */
  private TestResult test(Channel channel, String to, List<Route> routes) {
    String address = address(channel, to);
    String subject = "Pharmacy Core test message / Ujumbe wa majaribio";
    List<String> failures = new ArrayList<>();
    Route used = routes.getFirst();
    String error = null;
    for (Route route : routes) {
      used = route;
      String text =
          "This is a test message from Pharmacy Core through "
              + route.label()
              + ". / Huu ni ujumbe wa majaribio kutoka Pharmacy Core.";
      try {
        route.sender().send(address, subject, "<p>" + text + "</p>", text);
        error = null;
        break;
      } catch (RuntimeException failure) {
        String readable =
            tz.co.pharmacy.modules.notifications.providers.ProviderErrors.readable(
                failure, route.provider());
        failures.add(route.label() + ": " + readable);
        error = String.join(" | ", failures);
        LOG.warn("Provider test via {} failed: {}", route.label(), failure.toString());
      }
    }
    String outcome = error;
    Route sentBy = used;
    String text = "Pharmacy Core test message through " + sentBy.label() + ".";
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
            message.sent(sentBy.label());
          } else {
            message.failed(sentBy.label(), outcome, null);
          }
          messages.save(message);
        });
    return new TestResult(error == null, sentBy.label(), sentBy.source(), error);
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

  private ChannelView channelView(
      Channel channel, List<ProviderSetting> rows, String mode, List<Route> routes) {
    Optional<Message> lastSent =
        messages.findFirstByChannelAndStatusOrderBySentAtDesc(channel, MessageStatus.SENT);
    Optional<Message> lastFailed = messages.latestByStatus(channel, MessageStatus.FAILED);
    return new ChannelView(
        channel,
        mode,
        routes.getFirst().source(),
        routes.stream().map(Route::label).toList(),
        rows.stream().map(this::view).toList(),
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

  private ProviderView view(ProviderSetting row) {
    Optional<Definition> definition = ProviderCatalog.find(row.getChannel(), row.getProvider());
    Map<String, String> plain = ProviderRouter.plain(row);
    boolean secretsUnreadable = !router.secretsReadable(row);
    Map<String, String> secrets = secretsUnreadable ? Map.of() : router.secrets(row);
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
            .orElse(false);
    return new ProviderView(
        row.getUid(),
        row.getChannel(),
        row.getName(),
        row.getProvider(),
        row.getPriority(),
        row.isActive(),
        plain,
        masked,
        configured,
        secretsUnreadable,
        health.tripped(row.getUid()),
        row.getLastSentAt(),
        row.getLastFailedAt(),
        row.getLastError());
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

  private static Refusal notFound() {
    return Refusal.notFound("provider_not_found", "No such provider.");
  }

  /** Runs work in the platform scope (no tenant), where the platform's rows are written. */
  private static <T> T asPlatform(Supplier<T> work) {
    return TenantContext.callAs(null, work);
  }
}
