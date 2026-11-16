package tz.co.pharmacy.modules.catalogue.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.pharmacy.modules.catalogue.domain.FormulaItem;

/** Row-level security limits every query to the current tenant. */
public interface FormulaItemRepository extends JpaRepository<FormulaItem, Long> {

  List<FormulaItem> findByProductUidOrderByIdAsc(UUID productUid);

  void deleteByProductUid(UUID productUid);
}
