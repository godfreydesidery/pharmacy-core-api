package tz.co.pharmacy.modules.integrations.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tz.co.pharmacy.modules.integrations.domain.Webhook;

public interface WebhookRepository extends JpaRepository<Webhook, Long> {

  Optional<Webhook> findByUid(UUID uid);

  List<Webhook> findAllByOrderByNameAsc();

  List<Webhook> findAllByActiveTrue();

  /** Webhooks saved before secrets were sealed (their secret is still {@code whsec_…}). */
  @Query(
      "select w from Webhook w where substring(w.secret, 1, 6) = 'whsec_'"
          + " or substring(w.previousSecret, 1, 6) = 'whsec_'")
  List<Webhook> findWithPlainSecrets();

  /**
   * Takes a transaction-scoped PostgreSQL advisory lock named {@code key}, so that two instances
   * creating a webhook for one pharmacy at once count one after the other.
   */
  @Query(
      value = "select count(*) from (select pg_advisory_xact_lock(hashtextextended(:key, 0))) l",
      nativeQuery = true)
  long lock(@Param("key") String key);
}
