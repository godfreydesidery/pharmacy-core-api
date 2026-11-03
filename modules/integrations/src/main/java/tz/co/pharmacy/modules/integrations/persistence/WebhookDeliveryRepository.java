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
}
