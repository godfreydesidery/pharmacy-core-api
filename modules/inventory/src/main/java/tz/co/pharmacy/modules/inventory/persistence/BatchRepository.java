package tz.co.pharmacy.modules.inventory.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.pharmacy.modules.inventory.domain.Batch;

public interface BatchRepository extends JpaRepository<Batch, Long> {

  Optional<Batch> findByUid(UUID uid);

  Optional<Batch> findByProductUidAndBatchNumber(UUID productUid, String batchNumber);
}
