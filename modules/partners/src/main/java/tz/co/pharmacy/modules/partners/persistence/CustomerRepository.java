package tz.co.pharmacy.modules.partners.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import tz.co.pharmacy.modules.partners.domain.Customer;

/** Row-level security limits every query to the current tenant. */
public interface CustomerRepository
    extends JpaRepository<Customer, Long>, JpaSpecificationExecutor<Customer> {

  Optional<Customer> findByUid(UUID uid);
}
