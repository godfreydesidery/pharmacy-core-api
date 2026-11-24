package tz.co.pharmacy.modules.partners.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import tz.co.pharmacy.modules.partners.domain.DataSubjectRequest;

/** Row-level security limits every query to the current tenant. */
public interface DataSubjectRequestRepository
    extends JpaRepository<DataSubjectRequest, Long>, JpaSpecificationExecutor<DataSubjectRequest> {

  Optional<DataSubjectRequest> findByUid(UUID uid);
}
