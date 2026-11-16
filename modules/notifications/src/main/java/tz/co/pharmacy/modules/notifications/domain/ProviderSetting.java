package tz.co.pharmacy.modules.notifications.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.modules.notifications.ports.inbound.Channel;

/**
 * The provider a channel sends through: the platform's default (no tenant) or a pharmacy's own
 * account (mode OWN), or a pharmacy's choice of the platform's (mode PLATFORM). {@code settings} is
 * JSON of the plain fields; {@code secrets} the sealed JSON of the secret ones.
 */
@Getter
@Entity
@Table(schema = "notifications", name = "provider_settings")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProviderSetting extends ScopedEntity {

  public static final String PLATFORM = "PLATFORM";
  public static final String OWN = "OWN";

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false)
  private Channel channel;

  @Column(nullable = false)
  private String mode;

  @Column(nullable = false)
  private String provider;

  @Column(nullable = false)
  private String settings = "{}";

  private String secrets;

  public ProviderSetting(Channel channel) {
    this.channel = channel;
    this.mode = OWN;
    this.provider = "log";
  }

  public void change(String mode, String provider, String settings, String secrets) {
    this.mode = mode;
    this.provider = provider;
    this.settings = settings;
    this.secrets = secrets;
  }

  public boolean own() {
    return OWN.equals(mode);
  }
}
