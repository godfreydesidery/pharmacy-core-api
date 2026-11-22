package tz.co.pharmacy.modules.inventory.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import tz.co.pharmacy.modules.inventory.domain.StockCount;

/** Row-level security limits every query to the current tenant. */
public interface StockCountRepository
    extends JpaRepository<StockCount, Long>, JpaSpecificationExecutor<StockCount> {

  @EntityGraph(attributePaths = "lines")
  Optional<StockCount> findByUid(UUID uid);

  List<StockCount> findTop50ByBranchUidOrderByIdDesc(UUID branchUid);
}
