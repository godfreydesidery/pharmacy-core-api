package tz.co.pharmacy.modules.finance.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import tz.co.pharmacy.modules.finance.domain.SupplierPayment;

/** Row-level security limits every query to the current tenant. */
public interface SupplierPaymentRepository
    extends JpaRepository<SupplierPayment, Long>, JpaSpecificationExecutor<SupplierPayment> {}
