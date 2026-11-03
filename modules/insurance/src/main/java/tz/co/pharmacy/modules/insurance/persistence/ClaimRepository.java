package tz.co.pharmacy.modules.insurance.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import tz.co.pharmacy.modules.insurance.domain.Claim;

/** Row-level security limits every query to the current tenant. */
public interface ClaimRepository
    extends JpaRepository<Claim, Long>, JpaSpecificationExecutor<Claim> {

  Optional<Claim> findByUid(UUID uid);

  Optional<Claim> findFirstBySaleUidOrderByIdDesc(UUID saleUid);

  List<Claim> findByBatchUidOrderByIdAsc(UUID batchUid);

  @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @org.springframework.data.jpa.repository.Query(
      "select c from Claim c where c.payerUid = :payer and c.status in (tz.co.pharmacy.modules.insurance.ports.inbound.ClaimStatus.APPROVED, tz.co.pharmacy.modules.insurance.ports.inbound.ClaimStatus.PARTIALLY_APPROVED) order by c.id")
  List<Claim> payable(UUID payer);

  @org.springframework.data.jpa.repository.Query(
      "select c from Claim c where c.payerUid = :payer and c.status = tz.co.pharmacy.modules.insurance.ports.inbound.ClaimStatus.DRAFT and c.createdAt >= :from and c.createdAt < :to order by c.id")
  List<Claim> drafts(UUID payer, java.time.Instant from, java.time.Instant to);
}
