package tz.co.pharmacy.modules.integrations.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tz.co.pharmacy.modules.integrations.domain.WebhookDelivery;

public interface WebhookDeliveryRepository
    extends JpaRepository<WebhookDelivery, Long>, JpaSpecificationExecutor<WebhookDelivery> {

  Optional<WebhookDelivery> findByUid(UUID uid);

  boolean existsByWebhookUidAndEventId(UUID webhookUid, UUID eventId);

  /**
   * The current pharmacy's failed deliveries whose next attempt is due, oldest first, up to 100,
   * locked for this transaction; rows another instance has locked are skipped ({@code FOR UPDATE
   * SKIP LOCKED}), so two senders never claim the same delivery.
   */
  @Query(
      value =
          "select * from integrations.deliveries where status = 'FAILED'"
              + " and next_attempt_at <= :now order by next_attempt_at limit 100"
              + " for update skip locked",
      nativeQuery = true)
  List<WebhookDelivery> lockDue(@Param("now") Instant now);
}
