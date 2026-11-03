package tz.co.pharmacy.modules.tenancy.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.pharmacy.modules.tenancy.domain.TenantSettings;

/** The current tenant's settings row: row-level security leaves at most one. */
public interface SettingsRepository extends JpaRepository<TenantSettings, Long> {

  Optional<TenantSettings> findFirstByOrderByIdAsc();
}
