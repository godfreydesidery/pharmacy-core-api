package tz.co.pharmacy.modules.partners.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.pharmacy.modules.partners.domain.CustomerConsent;
import tz.co.pharmacy.modules.partners.ports.inbound.ConsentPurpose;

/** Row-level security limits every query to the current tenant. */
public interface CustomerConsentRepository extends JpaRepository<CustomerConsent, Long> {

  List<CustomerConsent> findByCustomerUidOrderByIdDesc(UUID customerUid);

  Optional<CustomerConsent> findFirstByCustomerUidAndPurposeOrderByIdDesc(
      UUID customerUid, ConsentPurpose purpose);
}
