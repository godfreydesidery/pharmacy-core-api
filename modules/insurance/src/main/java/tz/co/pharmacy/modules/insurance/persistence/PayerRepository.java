package tz.co.pharmacy.modules.insurance.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import tz.co.pharmacy.modules.insurance.domain.Payer;

/** Row-level security limits every query to the current tenant. */
public interface PayerRepository
    extends JpaRepository<Payer, Long>, JpaSpecificationExecutor<Payer> {

  Optional<Payer> findByUid(UUID uid);

  Optional<Payer> findByCodeIgnoreCase(String code);

  List<Payer> findAllByOrderByNameAsc();
}
