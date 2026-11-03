package tz.co.pharmacy.modules.receivables.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.pharmacy.modules.receivables.domain.CreditAccount;

/** Row-level security limits every query to the current tenant. */
public interface CreditAccountRepository extends JpaRepository<CreditAccount, Long> {

  Optional<CreditAccount> findByCustomerUid(UUID customerUid);
}
