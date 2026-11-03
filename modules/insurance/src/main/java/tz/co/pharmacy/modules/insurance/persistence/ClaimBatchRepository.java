package tz.co.pharmacy.modules.insurance.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import tz.co.pharmacy.modules.insurance.domain.ClaimBatch;

/** Row-level security limits every query to the current tenant. */
public interface ClaimBatchRepository
    extends JpaRepository<ClaimBatch, Long>, JpaSpecificationExecutor<ClaimBatch> {

  Optional<ClaimBatch> findByUid(UUID uid);

  List<ClaimBatch> findAllByOrderByIdDesc();

  List<ClaimBatch> findByPayerUidOrderByIdDesc(UUID payerUid);
}
