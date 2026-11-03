package tz.co.pharmacy.modules.insurance.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import tz.co.pharmacy.modules.insurance.domain.Scheme;

/** Row-level security limits every query to the current tenant. */
public interface SchemeRepository
    extends JpaRepository<Scheme, Long>, JpaSpecificationExecutor<Scheme> {

  Optional<Scheme> findByUid(UUID uid);

  List<Scheme> findAllByOrderByNameAsc();

  List<Scheme> findByPayerUidOrderByNameAsc(UUID payerUid);
}
