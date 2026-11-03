package tz.co.pharmacy.modules.purchasing.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import tz.co.pharmacy.modules.purchasing.domain.GoodsReceipt;

public interface GoodsReceiptRepository
    extends JpaRepository<GoodsReceipt, Long>, JpaSpecificationExecutor<GoodsReceipt> {

  Optional<GoodsReceipt> findByUid(UUID uid);

  @Query(
      "select coalesce(sum(r.baseTotal), 0) from GoodsReceipt r"
          + " where r.createdAt >= :from and r.createdAt < :to")
  BigDecimal totalBetween(Instant from, Instant to);

  @Query(
      "select coalesce(sum(r.baseTotal), 0) from GoodsReceipt r"
          + " where r.branchUid in :branchUids and r.createdAt >= :from and r.createdAt < :to")
  BigDecimal totalBetween(Collection<UUID> branchUids, Instant from, Instant to);

  java.util.List<GoodsReceipt> findAllByUidIn(java.util.Collection<UUID> uids);
}
