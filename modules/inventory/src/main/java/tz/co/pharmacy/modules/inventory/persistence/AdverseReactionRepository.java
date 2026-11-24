package tz.co.pharmacy.modules.inventory.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import tz.co.pharmacy.modules.inventory.domain.AdverseReaction;

/** Row-level security limits every query to the current tenant. */
public interface AdverseReactionRepository
    extends JpaRepository<AdverseReaction, Long>, JpaSpecificationExecutor<AdverseReaction> {

  Optional<AdverseReaction> findByUid(UUID uid);
}
