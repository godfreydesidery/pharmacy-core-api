package tz.co.pharmacy.modules.tenancy.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import tz.co.pharmacy.modules.tenancy.domain.Tenant;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantStatus;

public interface TenantRepository
    extends JpaRepository<Tenant, Long>, JpaSpecificationExecutor<Tenant> {

  Optional<Tenant> findByUid(UUID uid);

  boolean existsByCode(String code);

  long countByStatus(TenantStatus status);
}
