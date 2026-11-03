package tz.co.pharmacy.modules.integrations.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.pharmacy.modules.integrations.domain.Webhook;

public interface WebhookRepository extends JpaRepository<Webhook, Long> {

  Optional<Webhook> findByUid(UUID uid);

  List<Webhook> findAllByOrderByNameAsc();

  List<Webhook> findAllByActiveTrue();
}
