package tz.co.pharmacy.modules.sales.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import tz.co.pharmacy.modules.sales.domain.Sale;

public interface SaleRepository extends JpaRepository<Sale, Long>, JpaSpecificationExecutor<Sale> {

  Optional<Sale> findByUid(UUID uid);

  Optional<Sale> findByIdempotencyKey(String idempotencyKey);
}
