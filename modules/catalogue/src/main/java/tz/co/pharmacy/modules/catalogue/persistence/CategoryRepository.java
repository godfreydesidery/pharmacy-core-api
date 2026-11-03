package tz.co.pharmacy.modules.catalogue.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.pharmacy.modules.catalogue.domain.Category;

/** Row-level security limits every query to the current tenant. */
public interface CategoryRepository extends JpaRepository<Category, Long> {

  Optional<Category> findByUid(UUID uid);

  @EntityGraph(attributePaths = {"parent"})
  List<Category> findAllByOrderByNameAsc();

  boolean existsByNameIgnoreCase(String name);

  boolean existsByNameIgnoreCaseAndUidNot(String name, UUID uid);
}
