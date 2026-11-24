package tz.co.pharmacy.modules.sales.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.pharmacy.modules.sales.domain.TillEvent;

/** Row-level security limits every query to the current tenant. */
public interface TillEventRepository extends JpaRepository<TillEvent, Long> {}
