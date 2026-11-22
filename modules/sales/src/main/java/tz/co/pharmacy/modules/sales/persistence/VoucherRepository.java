package tz.co.pharmacy.modules.sales.persistence;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import tz.co.pharmacy.modules.sales.domain.Voucher;

/** Row-level security limits every query to the current tenant. */
public interface VoucherRepository
    extends JpaRepository<Voucher, Long>, JpaSpecificationExecutor<Voucher> {

  /** The voucher, locked, so two tills cannot spend the same balance. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select v from Voucher v where upper(v.code) = upper(:code)")
  Optional<Voucher> lockByCode(String code);

  boolean existsByCodeIgnoreCase(String code);

  List<Voucher> findTop200ByOrderByIdDesc();
}
