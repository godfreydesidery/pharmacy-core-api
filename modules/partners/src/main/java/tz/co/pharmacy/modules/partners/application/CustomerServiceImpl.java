package tz.co.pharmacy.modules.partners.application;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.partners.domain.Customer;
import tz.co.pharmacy.modules.partners.persistence.CustomerRepository;
import tz.co.pharmacy.modules.partners.ports.inbound.CustomerCommand;
import tz.co.pharmacy.modules.partners.ports.inbound.CustomerService;
import tz.co.pharmacy.modules.partners.ports.inbound.CustomerType;
import tz.co.pharmacy.modules.partners.ports.inbound.CustomerView;
import tz.co.pharmacy.modules.partners.ports.inbound.PaymentTerms;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.kernel.TenantLocale;
import tz.co.pharmacy.platform.persistence.DocumentNumbers;
import tz.co.pharmacy.platform.persistence.Keyset;
import tz.co.pharmacy.platform.persistence.Specs;

/** The customers of the current tenant. */
@Service
@RequiredArgsConstructor
class CustomerServiceImpl implements CustomerService {

  private final CustomerRepository customers;
  private final DocumentNumbers numbers;
  private final TenantLocale locale;

  @Override
  @Transactional(readOnly = true)
  public Page<CustomerView> search(String q, CustomerType type, PageQuery query) {
    return Keyset.page(
        customers,
        PartnerPhones.<Customer>search(q, "name", "phone", "email", "code")
            .and(Specs.equal("type", type)),
        query,
        CustomerServiceImpl::view);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<CustomerView> find(UUID uid) {
    return customers.findByUid(uid).map(CustomerServiceImpl::view);
  }

  @Override
  @Transactional
  public CustomerView create(CustomerCommand c) {
    Customer.Details details = details(c, true, null);
    return view(customers.save(new Customer(numbers.format("CUS", 6), details)));
  }

  @Override
  @Transactional
  public CustomerView change(UUID uid, CustomerCommand c) {
    Customer customer =
        customers
            .findByUid(uid)
            .orElseThrow(() -> Refusal.notFound("customer_not_found", "No such customer."));
    if (customer.isAnonymised()) {
      throw anonymised();
    }
    customer.change(details(c, customer.isActive(), customer.getPhone()));
    // Flushed, so the view carries the version this change gave the row (its new ETag).
    customers.flush();
    return view(customer);
  }

  @Override
  @Transactional(readOnly = true)
  public long count() {
    return customers.count();
  }

  private Customer.Details details(
      CustomerCommand c, boolean currentlyActive, String currentPhone) {
    BigDecimal creditLimit = c.creditLimit() == null ? BigDecimal.ZERO : c.creditLimit();
    return new Customer.Details(
        Checks.text(c.name(), "name", 200),
        PartnerPhones.phone(c.phone(), currentPhone, locale.country()),
        optionalEmail(c.email()),
        c.type() == null ? CustomerType.RETAIL : c.type(),
        Checks.optionalText(c.insuranceProvider(), "insuranceProvider", 200),
        Checks.optionalText(c.insuranceNumber(), "insuranceNumber", 100),
        Checks.notNegative(creditLimit, "creditLimit"),
        Checks.optionalText(c.address(), "address", 500),
        c.active() == null ? currentlyActive : c.active(),
        c.paymentTerms() == null ? PaymentTerms.NET_30 : c.paymentTerms());
  }

  /** 409 customer_anonymised: an anonymised customer is no longer a person on record. */
  static Refusal anonymised() {
    return Refusal.conflict(
        "customer_anonymised",
        "This customer's personal data was anonymised; the record can no longer be changed.");
  }

  private static String optionalEmail(String email) {
    return email == null || email.isBlank() ? null : Checks.email(email, "email");
  }

  private static CustomerView view(Customer c) {
    return new CustomerView(
        c.getUid(),
        c.getCode(),
        c.getName(),
        c.getPhone(),
        c.getEmail(),
        c.getType(),
        c.getInsuranceProvider(),
        c.getInsuranceNumber(),
        c.getCreditLimit(),
        c.getAddress(),
        c.isActive(),
        c.getCreatedAt(),
        c.getPaymentTerms(),
        c.getAnonymisedAt(),
        c.getVersion());
  }
}
