package tz.co.pharmacy.modules.inventory.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import tz.co.pharmacy.modules.inventory.domain.Destruction;

/** Row-level security limits every query to the current tenant. */
public interface DestructionRepository
    extends JpaRepository<Destruction, Long>, JpaSpecificationExecutor<Destruction> {

  @EntityGraph(attributePaths = "lines")
  Optional<Destruction> findByUid(UUID uid);

  @EntityGraph(attributePaths = "lines")
  List<Destruction> findTop100ByBranchUidOrderByIdDesc(UUID branchUid);
}
