package tz.co.pharmacy.modules.tenancy.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.pharmacy.modules.tenancy.domain.Plan;

/** Plans are platform records (no tenant isolation). */
public interface PlanRepository extends JpaRepository<Plan, Long> {

  Optional<Plan> findByUid(UUID uid);

  Optional<Plan> findByCode(String code);

  boolean existsByCode(String code);

  List<Plan> findAllByOrderBySortOrderAscCodeAsc();
}
