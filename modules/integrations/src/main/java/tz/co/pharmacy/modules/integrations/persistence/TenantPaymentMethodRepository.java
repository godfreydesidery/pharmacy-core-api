package tz.co.pharmacy.modules.integrations.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.pharmacy.modules.integrations.domain.TenantPaymentMethod;

/** Row-level security limits every query to the current tenant. */
public interface TenantPaymentMethodRepository extends JpaRepository<TenantPaymentMethod, Long> {

  Optional<TenantPaymentMethod> findByMethodCode(String methodCode);

  List<TenantPaymentMethod> findAllByOrderByIdAsc();
}
