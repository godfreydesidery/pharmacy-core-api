package tz.co.pharmacy.modules.purchasing.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import tz.co.pharmacy.modules.purchasing.domain.SupplierReturn;

/** Row-level security limits every query to the current tenant. */
public interface SupplierReturnRepository
    extends JpaRepository<SupplierReturn, Long>, JpaSpecificationExecutor<SupplierReturn> {

  @EntityGraph(attributePaths = "lines")
  Optional<SupplierReturn> findByUid(UUID uid);

  @EntityGraph(attributePaths = "lines")
  List<SupplierReturn> findTop100ByBranchUidOrderByIdDesc(UUID branchUid);

  @EntityGraph(attributePaths = "lines")
  List<SupplierReturn> findTop100ByBranchUidAndSupplierUidOrderByIdDesc(
      UUID branchUid, UUID supplierUid);
}
