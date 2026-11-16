package tz.co.pharmacy.modules.integrations.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.pharmacy.modules.integrations.domain.PaymentMethodType;

/** The platform's payment-method registry (not tenant data). */
public interface PaymentMethodTypeRepository extends JpaRepository<PaymentMethodType, Long> {

  Optional<PaymentMethodType> findByCode(String code);

  List<PaymentMethodType> findAllByOrderBySortOrderAscCodeAsc();
}
