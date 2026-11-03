package tz.co.pharmacy.modules.tenancy.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.pharmacy.modules.tenancy.domain.Branch;

/** Branches of the current tenant: row-level security limits every query to it. */
public interface BranchRepository extends JpaRepository<Branch, Long> {

  Optional<Branch> findByUid(UUID uid);

  boolean existsByCode(String code);

  List<Branch> findAllByOrderByHeadOfficeDescNameAsc();
}
