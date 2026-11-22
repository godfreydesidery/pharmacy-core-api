package tz.co.pharmacy.modules.integrations.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.modules.integrations.ports.inbound.DeliveryStatus;
import tz.co.pharmacy.platform.kernel.Clocks;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/** One event sent, or being sent, to one webhook, with the outcome of its last attempt. */
@Getter
@Entity
@Table(schema = "integrations", name = "deliveries")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WebhookDelivery extends TenantEntity {

  @Column(nullable = false)
  private UUID webhookUid;

  @Column(nullable = false)
  private String eventType;

  @Column(nullable = false)
  private UUID eventId;

  @Column(nullable = false)
  private String payload;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private DeliveryStatus status;

  @Column(nullable = false)
  private int attempts;

  private Integer responseStatus;

  private String lastError;

  private Instant deliveredAt;

  /** Rounds of attempts made (each round is up to three quick tries). */
  private int rounds;

  /** When a failed delivery is next tried; null once delivered or given up. */
  private Instant nextAttemptAt;

  /**
   * Waits after the 1st, 2nd... failed round: about two days in all, then the delivery gives up.
   */
  static final java.util.List<java.time.Duration> BACKOFF =
      java.util.List.of(
          java.time.Duration.ofMinutes(1),
          java.time.Duration.ofMinutes(5),
          java.time.Duration.ofMinutes(30),
          java.time.Duration.ofHours(2),
          java.time.Duration.ofHours(6),
          java.time.Duration.ofHours(12),
          java.time.Duration.ofHours(24));

  public WebhookDelivery(UUID webhookUid, String eventType, UUID eventId, String payload) {
    this.webhookUid = webhookUid;
    this.eventType = eventType;
    this.eventId = eventId;
    this.payload = payload;
    this.status = DeliveryStatus.PENDING;
  }

  /**
   * Records one round of attempts; a failed round schedules the next one (see {@link #BACKOFF}).
   */
  public void recordAttempt(int attempts, Integer responseStatus, String error, boolean delivered) {
    Instant now = Clocks.now();
    this.attempts += attempts;
    this.rounds++;
    this.responseStatus = responseStatus;
    this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 500));
    this.status = delivered ? DeliveryStatus.DELIVERED : DeliveryStatus.FAILED;
    this.deliveredAt = delivered ? now : null;
    this.nextAttemptAt =
        delivered || rounds > BACKOFF.size() ? null : now.plus(BACKOFF.get(rounds - 1));
  }

  /** The webhook is gone or switched off: no more attempts. */
  public void giveUp(String reason) {
    this.nextAttemptAt = null;
    this.lastError = reason;
  }
}
