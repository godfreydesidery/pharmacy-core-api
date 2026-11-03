package tz.co.pharmacy.modules.inventory.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import tz.co.pharmacy.modules.inventory.domain.Movement;

public interface MovementRepository
    extends JpaRepository<Movement, Long>, JpaSpecificationExecutor<Movement> {}
