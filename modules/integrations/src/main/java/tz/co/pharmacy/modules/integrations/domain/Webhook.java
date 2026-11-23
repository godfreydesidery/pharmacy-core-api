package tz.co.pharmacy.modules.integrations.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/**
 * An external endpoint that receives the tenant's events of the types it subscribes to. Its signing
 * secret is kept sealed with the platform key; a webhook saved before sealing holds it in plain
 * text ({@code whsec_…}) until it is next used, when it is sealed in place. After a rotation the
 * previous secret is kept, sealed, for a day, and deliveries are signed with both.
 */
@Getter
@Entity
@Table(schema = "integrations", name = "webhooks")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Webhook extends TenantEntity {

  /** How a plain (not yet sealed) secret starts; a sealed one is base64 and never does. */
  public static final String PLAIN_PREFIX = "whsec_";

  @Column(nullable = false)
  private String name;

  @Column(nullable = false)
  private String url;

  /** The signing secret, sealed (or, for a webhook saved before sealing, plain {@code whsec_…}). */
  @Column(nullable = false)
  private String secret;

  /** The secret before the last rotation, sealed, still signing until {@link #previousUntil}. */
  private String previousSecret;

  private Instant previousUntil;

  /** The subscribed event types, comma-joined. */
  @Column(nullable = false)
  private String eventTypes;

  @Column(nullable = false)
  private boolean active;

  public Webhook(
      String name, String url, String sealedSecret, List<String> eventTypes, boolean active) {
    this.secret = sealedSecret;
    change(name, url, eventTypes, active);
  }

  public void change(String name, String url, List<String> eventTypes, boolean active) {
    this.name = name;
    this.url = url;
    this.eventTypes = String.join(",", eventTypes);
    this.active = active;
  }

  public boolean secretInPlainText() {
    return secret.startsWith(PLAIN_PREFIX)
        || (previousSecret != null && previousSecret.startsWith(PLAIN_PREFIX));
  }

  /** Replaces the stored secrets by their sealed form (same secrets, sealed at rest). */
  public void sealed(String sealedSecret, String sealedPrevious) {
    this.secret = sealedSecret;
    this.previousSecret = sealedPrevious;
  }

  /**
   * A new signing secret; the current one keeps signing until {@code previousUntil}, so the
   * receiver can switch over without missing a delivery.
   */
  public void rotate(String sealedNew, String sealedCurrent, Instant previousUntil) {
    this.previousSecret = sealedCurrent;
    this.previousUntil = previousUntil;
    this.secret = sealedNew;
  }

  /** Whether the previous secret still signs at {@code now}. */
  public boolean previousSigns(Instant now) {
    return previousSecret != null && previousUntil != null && now.isBefore(previousUntil);
  }

  public List<String> eventTypeList() {
    return eventTypes.isBlank() ? List.of() : Arrays.asList(eventTypes.split(","));
  }

  public boolean subscribesTo(String eventType) {
    return active && eventTypeList().contains(eventType);
  }
}
