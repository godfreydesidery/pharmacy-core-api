package tz.co.pharmacy.modules.inventory.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.pharmacy.modules.inventory.domain.ProductRecord;

public interface ProductRecordRepository extends JpaRepository<ProductRecord, Long> {

  Optional<ProductRecord> findByProductUid(UUID productUid);
}
