package tz.co.pharmacy.modules.tenancy.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.pharmacy.modules.tenancy.domain.Subscription;
import tz.co.pharmacy.modules.tenancy.ports.inbound.SubscriptionStatus;

/** Subscriptions are platform records; every query names its tenant. */
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

  Optional<Subscription> findByTenantUid(UUID tenantUid);

  List<Subscription> findByStatusIn(List<SubscriptionStatus> statuses);

  long countByStatus(SubscriptionStatus status);
}
