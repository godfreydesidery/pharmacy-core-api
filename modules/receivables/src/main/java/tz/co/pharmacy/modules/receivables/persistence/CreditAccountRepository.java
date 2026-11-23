package tz.co.pharmacy.modules.receivables.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.pharmacy.modules.receivables.domain.CreditAccount;

/** Row-level security limits every query to the current tenant. */
public interface CreditAccountRepository extends JpaRepository<CreditAccount, Long> {

  Optional<CreditAccount> findByCustomerUid(UUID customerUid);

  /**
   * Holds the customer's credit lock until the transaction ends: a second credit sale for the same
   * customer waits here, then reads the balance the first one committed. An advisory lock rather
   * than the account row, because a customer's first credit sale has no account row yet.
   */
  @org.springframework.data.jpa.repository.Query(
      value =
          "select 1 from (select pg_advisory_xact_lock(hashtextextended('receivables.credit:'"
              + " || cast(:customerUid as text), 0))) held",
      nativeQuery = true)
  Integer lockCredit(
      @org.springframework.data.repository.query.Param("customerUid") UUID customerUid);
}
