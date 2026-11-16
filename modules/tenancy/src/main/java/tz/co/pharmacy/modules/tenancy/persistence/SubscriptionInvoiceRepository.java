package tz.co.pharmacy.modules.tenancy.persistence;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import tz.co.pharmacy.modules.tenancy.domain.SubscriptionInvoice;

/** Invoices are platform records; every query names its tenant. */
public interface SubscriptionInvoiceRepository extends JpaRepository<SubscriptionInvoice, Long> {

  Optional<SubscriptionInvoice> findByUid(UUID uid);

  List<SubscriptionInvoice> findByTenantUidOrderByIdDesc(UUID tenantUid);

  Optional<SubscriptionInvoice> findFirstByTenantUidAndStatusOrderByIdAsc(
      UUID tenantUid, String status);

  @Query("select coalesce(sum(i.amount), 0) from SubscriptionInvoice i where i.status = 'OPEN'")
  BigDecimal outstanding();

  @Query(value = "select nextval('tenancy.subscription_invoice_numbers')", nativeQuery = true)
  long nextNumber();
}
