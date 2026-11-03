package tz.co.pharmacy.modules.insurance.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import tz.co.pharmacy.modules.insurance.domain.Remittance;

/** Row-level security limits every query to the current tenant. */
public interface RemittanceRepository
    extends JpaRepository<Remittance, Long>, JpaSpecificationExecutor<Remittance> {

  List<Remittance> findAllByOrderByIdDesc();

  List<Remittance> findByPayerUidOrderByIdDesc(UUID payerUid);
}
