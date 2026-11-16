package tz.co.pharmacy.modules.catalogue.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.pharmacy.modules.catalogue.domain.BranchPrice;

/** Row-level security limits every query to the current tenant. */
public interface BranchPriceRepository extends JpaRepository<BranchPrice, Long> {

  Optional<BranchPrice> findByProductUidAndBranchUid(UUID productUid, UUID branchUid);

  List<BranchPrice> findByBranchUidAndProductUidIn(UUID branchUid, Collection<UUID> productUids);

  List<BranchPrice> findAllByOrderByProductUidAsc();
}
