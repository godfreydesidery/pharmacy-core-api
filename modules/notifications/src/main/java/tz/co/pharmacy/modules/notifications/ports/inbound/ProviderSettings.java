package tz.co.pharmacy.modules.notifications.ports.inbound;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The providers each channel (EMAIL, SMS, WHATSAPP) sends through. The platform keeps a list per
 * channel, set by a root administrator; a pharmacy may keep its own list, used instead while any of
 * it is active. A message tries a list's active providers in order until one accepts it (failover);
 * a provider that keeps failing is tried last for a while. Secrets are write-only: views show them
 * masked, and a change keeps any secret it does not name.
 */
public interface ProviderSettings {

  /** The platform's providers per channel (a platform session). */
  List<ChannelView> platform();

  /** Adds a provider at the end of the platform's list for the channel. */
  ProviderView addPlatform(Channel channel, ProviderCommand command);

  ProviderView changePlatform(UUID providerUid, ProviderCommand command);

  void removePlatform(UUID providerUid);

  /** The platform's providers of the channel in a new order (every uid of the channel once). */
  ChannelView reorderPlatform(Channel channel, List<UUID> order);

  /**
   * Sends a test message: through one provider ({@code providerUid}), or through the channel's list
   * as messages go (null), stopping at the first that accepts it.
   */
  TestResult testPlatform(Channel channel, UUID providerUid, String to);

  /** The current pharmacy's providers per channel, with where its messages go now. */
  List<ChannelView> tenant();

  ProviderView addTenant(Channel channel, ProviderCommand command);

  ProviderView changeTenant(UUID providerUid, ProviderCommand command);

  void removeTenant(UUID providerUid);

  ChannelView reorderTenant(Channel channel, List<UUID> order);

  TestResult testTenant(Channel channel, UUID providerUid, String to);

  /**
   * {@code name}: what people call this account ("Beem main", "Twilio backup"; the provider's code
   * when empty). {@code active}: whether messages use it (true when null on add, unchanged when
   * null on change). {@code secrets}: only the ones to set or replace; a blank value clears that
   * secret.
   */
  record ProviderCommand(
      String name,
      String provider,
      Boolean active,
      Map<String, String> settings,
      Map<String, String> secrets) {}

  /** A provider available on a channel and its fields. */
  record ProviderOption(String code, List<FieldView> fields) {}

  record FieldView(String key, boolean secret, boolean required) {}

  /**
   * One provider of a list. {@code secrets} masked ("••••1234"); {@code configured} when every
   * required field has a value; {@code secretsUnreadable} when the saved secrets were sealed under
   * another encryption key and must be entered again; {@code tripped} while its circuit breaker
   * holds it back after repeated failures.
   */
  record ProviderView(
      UUID uid,
      Channel channel,
      String name,
      String provider,
      int priority,
      boolean active,
      Map<String, String> settings,
      Map<String, String> secrets,
      boolean configured,
      boolean secretsUnreadable,
      boolean tripped,
      Instant lastSentAt,
      Instant lastFailedAt,
      String lastError) {}

  /**
   * A channel: {@code mode} OWN when a pharmacy uses its own list (or for the platform), PLATFORM
   * when it uses the platform's; {@code effectiveSource} (OWN, PLATFORM or ENVIRONMENT) and {@code
   * chain} (labels in the order tried) say where its messages go now; the last send and failure
   * come from the delivery log.
   */
  record ChannelView(
      Channel channel,
      String mode,
      String effectiveSource,
      List<String> chain,
      List<ProviderView> providers,
      Instant lastSentAt,
      Instant lastFailedAt,
      String lastError,
      List<ProviderOption> options) {}

  /** {@code provider}: the label of the provider that sent it (or tried last). */
  record TestResult(boolean ok, String provider, String source, String error) {}
}
