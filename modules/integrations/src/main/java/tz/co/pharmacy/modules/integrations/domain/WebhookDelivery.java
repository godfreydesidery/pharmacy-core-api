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

  public WebhookDelivery(UUID webhookUid, String eventType, UUID eventId, String payload) {
    this.webhookUid = webhookUid;
    this.eventType = eventType;
    this.eventId = eventId;
    this.payload = payload;
    this.status = DeliveryStatus.PENDING;
  }

  public void recordAttempt(int attempts, Integer responseStatus, String error, boolean delivered) {
    this.attempts = attempts;
    this.responseStatus = responseStatus;
    this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 500));
    this.status = delivered ? DeliveryStatus.DELIVERED : DeliveryStatus.FAILED;
    this.deliveredAt = delivered ? Clocks.now() : null;
  }
}
