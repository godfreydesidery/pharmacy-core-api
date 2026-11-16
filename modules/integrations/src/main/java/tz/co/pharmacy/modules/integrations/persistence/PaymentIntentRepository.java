package tz.co.pharmacy.modules.integrations.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import tz.co.pharmacy.modules.integrations.domain.PaymentIntent;

/** Row-level security limits every query to the current tenant. */
public interface PaymentIntentRepository extends JpaRepository<PaymentIntent, Long> {

  Optional<PaymentIntent> findByUid(UUID uid);

  /** The request, locked, so that it pays one sale only. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select i from PaymentIntent i where i.uid = :uid")
  Optional<PaymentIntent> lockByUid(UUID uid);
}
