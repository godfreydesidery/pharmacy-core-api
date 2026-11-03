package tz.co.pharmacy.modules.fx.persistence;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import tz.co.pharmacy.modules.fx.domain.ExchangeRate;

/** Row-level security limits every query to the current tenant. */
public interface ExchangeRateRepository
    extends JpaRepository<ExchangeRate, Long>, JpaSpecificationExecutor<ExchangeRate> {

  /** The latest rate of the pair effective on or before the day; the newest row wins a tie. */
  Optional<ExchangeRate>
      findFirstByBaseAndQuoteAndEffectiveOnLessThanEqualOrderByEffectiveOnDescIdDesc(
          String base, String quote, LocalDate on);

  Optional<ExchangeRate> findByBaseAndQuoteAndEffectiveOnAndSource(
      String base, String quote, LocalDate effectiveOn, String source);
}
