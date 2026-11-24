package tz.co.pharmacy.modules.partners.application;

import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.partners.domain.Supplier;
import tz.co.pharmacy.modules.partners.persistence.SupplierRepository;
import tz.co.pharmacy.modules.partners.ports.inbound.PaymentTerms;
import tz.co.pharmacy.modules.partners.ports.inbound.SupplierCommand;
import tz.co.pharmacy.modules.partners.ports.inbound.SupplierService;
import tz.co.pharmacy.modules.partners.ports.inbound.SupplierView;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.persistence.DocumentNumbers;
import tz.co.pharmacy.platform.persistence.Keyset;
import tz.co.pharmacy.platform.persistence.Specs;

/** The suppliers of the current tenant. */
@Service
@RequiredArgsConstructor
class SupplierServiceImpl implements SupplierService {

  private final SupplierRepository suppliers;
  private final DocumentNumbers numbers;

  @Override
  @Transactional(readOnly = true)
  public Page<SupplierView> search(String q, PageQuery query) {
    return Keyset.page(
        suppliers,
        Specs.<Supplier>containsAny(q, "name", "phone", "email", "code"),
        query,
        SupplierServiceImpl::view);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<SupplierView> find(UUID uid) {
    return suppliers.findByUid(uid).map(SupplierServiceImpl::view);
  }

  @Override
  @Transactional
  public SupplierView create(SupplierCommand c) {
    Supplier.Details details = details(c, true, 7);
    return view(suppliers.save(new Supplier(numbers.format("SUP", 6), details)));
  }

  @Override
  @Transactional
  public SupplierView change(UUID uid, SupplierCommand c) {
    Supplier supplier =
        suppliers
            .findByUid(uid)
            .orElseThrow(() -> Refusal.notFound("supplier_not_found", "No such supplier."));
    supplier.change(details(c, supplier.isActive(), supplier.getLeadTimeDays()));
    // Flushed, so the view carries the version this change gave the row (its new ETag).
    suppliers.flush();
    return view(supplier);
  }

  private static Supplier.Details details(
      SupplierCommand c, boolean currentlyActive, int currentLeadTime) {
    return new Supplier.Details(
        Checks.text(c.name(), "name", 200),
        Checks.optionalText(c.contactPerson(), "contactPerson", 200),
        Checks.optionalText(c.phone(), "phone", 40),
        optionalEmail(c.email()),
        Checks.optionalText(c.tin(), "tin", 50),
        c.paymentTerms() == null ? PaymentTerms.COD : c.paymentTerms(),
        Checks.optionalText(c.address(), "address", 500),
        c.active() == null ? currentlyActive : c.active(),
        leadTime(c.leadTimeDays(), currentLeadTime));
  }

  private static int leadTime(Integer days, int current) {
    if (days == null) {
      return current;
    }
    if (days < 0 || days > 365) {
      throw Refusal.invalid("invalid_lead_time", "Lead time is 0 to 365 days.", "leadTimeDays");
    }
    return days;
  }

  private static String optionalEmail(String email) {
    return email == null || email.isBlank() ? null : Checks.email(email, "email");
  }

  private static SupplierView view(Supplier s) {
    return new SupplierView(
        s.getUid(),
        s.getCode(),
        s.getName(),
        s.getContactPerson(),
        s.getPhone(),
        s.getEmail(),
        s.getTin(),
        s.getPaymentTerms(),
        s.getAddress(),
        s.isActive(),
        s.getCreatedAt(),
        s.getLeadTimeDays(),
        s.getVersion());
  }
}
