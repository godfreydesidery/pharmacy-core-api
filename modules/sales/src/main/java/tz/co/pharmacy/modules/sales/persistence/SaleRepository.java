package tz.co.pharmacy.modules.sales.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import tz.co.pharmacy.modules.sales.domain.Sale;

public interface SaleRepository extends JpaRepository<Sale, Long>, JpaSpecificationExecutor<Sale> {

  Optional<Sale> findByUid(UUID uid);

  Optional<Sale> findByIdempotencyKey(String idempotencyKey);

  /** Per plan name: sales, sum of totals, sum of normal totals. */
  @org.springframework.data.jpa.repository.Query(
      "select s.priceListName, count(s), sum(s.total), sum(s.normalTotal) from Sale s"
          + " where s.createdAt >= :from and s.createdAt < :to group by s.priceListName")
  java.util.List<Object[]> totalsByPlan(
      @org.springframework.data.repository.query.Param("from") java.time.Instant from,
      @org.springframework.data.repository.query.Param("to") java.time.Instant to);

  @org.springframework.data.jpa.repository.Query(
      "select s.priceListName, count(s), sum(s.total), sum(s.normalTotal) from Sale s"
          + " where s.createdAt >= :from and s.createdAt < :to and s.branchUid in :branches"
          + " group by s.priceListName")
  java.util.List<Object[]> totalsByPlan(
      @org.springframework.data.repository.query.Param("from") java.time.Instant from,
      @org.springframework.data.repository.query.Param("to") java.time.Instant to,
      @org.springframework.data.repository.query.Param("branches")
          java.util.Collection<UUID> branches);

  /**
   * Holds a lock named by {@code key} until the transaction ends: a second caller with the same key
   * waits here, then sees what the first one committed.
   */
  @org.springframework.data.jpa.repository.Query(
      value = "select 1 from (select pg_advisory_xact_lock(hashtextextended(:key, 0))) held",
      nativeQuery = true)
  Integer holdLock(@org.springframework.data.repository.query.Param("key") String key);

  /** Records the issued receipt (its reference, or none) and ends the lease. */
  @org.springframework.data.jpa.repository.Modifying
  @org.springframework.data.jpa.repository.Query(
      value =
          "update sales.sales set fiscal_reference = cast(:reference as text), fiscal_due = false,"
              + " fiscal_claimed_until = null, fiscal_status = 'ISSUED' where uid = :uid",
      nativeQuery = true)
  int recordFiscalReceipt(
      @org.springframework.data.repository.query.Param("uid") UUID uid,
      @org.springframework.data.repository.query.Param("reference") String reference);

  /** The receipt document's status beside the sale (PENDING again, or FAILED). */
  @org.springframework.data.jpa.repository.Modifying
  @org.springframework.data.jpa.repository.Query(
      value = "update sales.sales set fiscal_status = :status where uid = :uid",
      nativeQuery = true)
  int fiscalStatus(
      @org.springframework.data.repository.query.Param("uid") UUID uid,
      @org.springframework.data.repository.query.Param("status") String status);
}
