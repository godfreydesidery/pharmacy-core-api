package tz.co.pharmacy.modules.finance.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.pharmacy.modules.finance.domain.PeriodLock;

public interface PeriodLockRepository extends JpaRepository<PeriodLock, Long> {

  /** Row-level security keeps this to the current tenant, which has at most one. */
  Optional<PeriodLock> findFirstByOrderByIdAsc();
}
