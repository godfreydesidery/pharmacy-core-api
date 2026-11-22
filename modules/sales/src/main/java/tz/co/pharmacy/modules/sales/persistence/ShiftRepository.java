package tz.co.pharmacy.modules.sales.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.pharmacy.modules.sales.domain.Shift;

/** Row-level security limits every query to the current tenant. */
public interface ShiftRepository extends JpaRepository<Shift, Long> {

  /** The cashier's shift by account (who opened it), whatever email they sign in with. */
  Optional<Shift> findByBranchUidAndCreatedByUidAndStatus(
      UUID branchUid, UUID cashierUid, String status);

  List<Shift> findTop100ByBranchUidOrderByIdDesc(UUID branchUid);
}
