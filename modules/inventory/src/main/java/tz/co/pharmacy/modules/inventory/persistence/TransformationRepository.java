package tz.co.pharmacy.modules.inventory.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.pharmacy.modules.inventory.domain.Transformation;

/** Row-level security limits every query to the current tenant. */
public interface TransformationRepository extends JpaRepository<Transformation, Long> {

  @EntityGraph(attributePaths = "inputs")
  List<Transformation> findByBranchUidOrderByIdDesc(UUID branchUid, Pageable page);
}
