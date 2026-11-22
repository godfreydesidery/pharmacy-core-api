package tz.co.pharmacy.modules.notifications.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.modules.notifications.ports.inbound.Channel;

/**
 * One provider of a channel, in the platform's list (no tenant) or a pharmacy's own. A channel's
 * providers are tried in {@code priority} order, lowest first, and the first that accepts a message
 * sends it. {@code settings} is JSON of the plain fields; {@code secrets} the sealed JSON of the
 * secret ones. The last success and failure are kept for the settings page.
 */
@Getter
@Entity
@Table(schema = "notifications", name = "provider_settings")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProviderSetting extends ScopedEntity {

  /** Kept for rows written before lists: every row is now a provider of its own scope. */
  public static final String OWN = "OWN";

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false)
  private Channel channel;

  @Column(nullable = false)
  private String mode;

  @Column(nullable = false)
  private String provider;

  @Column(nullable = false)
  private String name;

  private int priority;

  private boolean active;

  @Column(nullable = false)
  private String settings = "{}";

  private String secrets;

  // Written by the dispatcher with bulk updates (no version bump), read by the settings page.
  @Column(insertable = false, updatable = false)
  private Instant lastSentAt;

  @Column(insertable = false, updatable = false)
  private Instant lastFailedAt;

  @Column(insertable = false, updatable = false)
  private String lastError;

  public ProviderSetting(Channel channel, int priority) {
    this.channel = channel;
    this.mode = OWN;
    this.provider = "log";
    this.name = "log";
    this.priority = priority;
    this.active = true;
  }

  public void change(String name, String provider, String settings, String secrets) {
    this.name = name;
    this.provider = provider;
    this.settings = settings;
    this.secrets = secrets;
  }

  public void activate(boolean active) {
    this.active = active;
  }

  public void moveTo(int priority) {
    this.priority = priority;
  }
}
