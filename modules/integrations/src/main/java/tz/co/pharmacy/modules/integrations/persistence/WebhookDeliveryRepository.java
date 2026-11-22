package tz.co.pharmacy.modules.integrations.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import tz.co.pharmacy.modules.integrations.domain.WebhookDelivery;

public interface WebhookDeliveryRepository
    extends JpaRepository<WebhookDelivery, Long>, JpaSpecificationExecutor<WebhookDelivery> {

  Optional<WebhookDelivery> findByUid(UUID uid);

  boolean existsByWebhookUidAndEventId(UUID webhookUid, UUID eventId);

  /** The current pharmacy's failed deliveries whose next attempt is due, oldest first. */
  java.util.List<WebhookDelivery>
      findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAt(
          tz.co.pharmacy.modules.integrations.ports.inbound.DeliveryStatus status,
          java.time.Instant now);
}
