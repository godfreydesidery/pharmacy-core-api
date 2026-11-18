package tz.co.pharmacy.modules.notifications.ports.inbound;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Which provider each channel (EMAIL, SMS, WHATSAPP) sends through. The platform's default is set
 * by a root administrator; a pharmacy uses it or connects its own account. Secrets are write-only:
 * views show them masked, and a save keeps any secret it does not name.
 */
public interface ProviderSettings {

  /** The platform's settings, per channel (a platform session). */
  List<ProviderSettingView> platform();

  ProviderSettingView savePlatform(Channel channel, ProviderSettingCommand command);

  /** Sends a test message through the platform's provider for the channel. */
  TestResult testPlatform(Channel channel, String to);

  /** The current pharmacy's settings, per channel, with the route its messages take. */
  List<ProviderSettingView> tenant();

  ProviderSettingView saveTenant(Channel channel, ProviderSettingCommand command);

  /** Sends a test message the way the current pharmacy's messages go. */
  TestResult testTenant(Channel channel, String to);

  /**
   * {@code mode}: PLATFORM (a pharmacy using the platform's) or OWN. {@code secrets}: only the ones
   * to set or replace; a blank value clears that secret.
   */
  record ProviderSettingCommand(
      String mode, String provider, Map<String, String> settings, Map<String, String> secrets) {}

  /** A provider available on a channel and its fields. */
  record ProviderOption(String code, List<FieldView> fields) {}

  record FieldView(String key, boolean secret, boolean required) {}

  /**
   * {@code secrets} masked ("••••1234"); {@code configured} when every required field has a value;
   * {@code secretsUnreadable} when the saved secrets were sealed under another encryption key and
   * must be entered again (they then count as missing); {@code effectiveProvider}/{@code
   * effectiveSource} (OWN, PLATFORM or ENVIRONMENT) say where the channel's messages go now; the
   * last send and failure come from the delivery log.
   */
  record ProviderSettingView(
      Channel channel,
      String mode,
      String provider,
      Map<String, String> settings,
      Map<String, String> secrets,
      boolean configured,
      boolean secretsUnreadable,
      String effectiveProvider,
      String effectiveSource,
      Instant lastSentAt,
      Instant lastFailedAt,
      String lastError,
      List<ProviderOption> options) {}

  record TestResult(boolean ok, String provider, String source, String error) {}
}
