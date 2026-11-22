package tz.co.pharmacy.modules.catalogue.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.pharmacy.modules.catalogue.domain.PriceChange;

public interface PriceChangeRepository extends JpaRepository<PriceChange, Long> {

  List<PriceChange> findTop200ByProductUidOrderByCreatedAtDescIdDesc(UUID productUid);
}
