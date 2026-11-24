package tz.co.pharmacy.modules.partners.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.partners.domain.Customer;
import tz.co.pharmacy.modules.partners.domain.CustomerConsent;
import tz.co.pharmacy.modules.partners.persistence.CustomerConsentRepository;
import tz.co.pharmacy.modules.partners.persistence.CustomerRepository;
import tz.co.pharmacy.modules.partners.ports.inbound.ConsentPurpose;
import tz.co.pharmacy.modules.partners.ports.inbound.ConsentView;
import tz.co.pharmacy.modules.partners.ports.inbound.ConsentsView;
import tz.co.pharmacy.modules.partners.ports.inbound.CustomerConsents;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * Consent records, one row per consent given (and later withdrawn). The current state of a purpose
 * is its newest row; a partial unique index keeps at most one row in force per customer and
 * purpose, so two instances recording at once cannot leave two.
 */
@Service
@RequiredArgsConstructor
class CustomerConsentsImpl implements CustomerConsents {

  private final CustomerRepository customers;
  private final CustomerConsentRepository consents;
  private final Clock clock;

  @Override
  @Transactional(readOnly = true)
  public ConsentsView consents(UUID customerUid) {
    customer(customerUid);
    return view(customerUid);
  }

  @Override
  @Transactional
  public ConsentsView record(UUID customerUid, ConsentPurpose purpose, boolean given, String note) {
    Customer customer = customer(customerUid);
    if (customer.isAnonymised()) {
      throw CustomerServiceImpl.anonymised();
    }
    Checks.required(purpose, "purpose");
    change(customerUid, purpose, given, Checks.optionalText(note, "note", 500));
    consents.flush();
    return view(customerUid);
  }

  @Override
  @Transactional
  public void apply(UUID customerUid, Map<ConsentPurpose, Boolean> choices) {
    if (choices == null || choices.isEmpty()) {
      return;
    }
    Customer customer = customer(customerUid);
    if (customer.isAnonymised()) {
      throw CustomerServiceImpl.anonymised();
    }
    choices.forEach(
        (purpose, given) -> {
          if (purpose != null && given != null) {
            change(customerUid, purpose, given, null);
          }
        });
  }

  @Override
  @Transactional(readOnly = true)
  public boolean allows(UUID customerUid, ConsentPurpose purpose) {
    Optional<CustomerConsent> latest =
        consents.findFirstByCustomerUidAndPurposeOrderByIdDesc(customerUid, purpose);
    return latest.map(CustomerConsent::inForce).orElse(purpose.allowedWithoutRecord());
  }

  private void change(UUID customerUid, ConsentPurpose purpose, boolean given, String note) {
    Optional<CustomerConsent> latest =
        consents.findFirstByCustomerUidAndPurposeOrderByIdDesc(customerUid, purpose);
    Instant now = clock.instant();
    String by = ExecutionContext.actor().name();
    if (given) {
      if (latest.map(CustomerConsent::inForce).orElse(false)) {
        return;
      }
      consents.save(CustomerConsent.given(customerUid, purpose, now, by, note));
    } else if (latest.isEmpty()) {
      consents.save(CustomerConsent.refused(customerUid, purpose, now, by, note));
    } else if (latest.get().inForce()) {
      latest.get().withdraw(now, by, note);
    }
  }

  private ConsentsView view(UUID customerUid) {
    List<CustomerConsent> rows = consents.findByCustomerUidOrderByIdDesc(customerUid);
    List<ConsentsView.Current> current = new ArrayList<>();
    for (ConsentPurpose purpose : ConsentPurpose.values()) {
      Optional<CustomerConsent> latest =
          rows.stream().filter(row -> row.getPurpose() == purpose).findFirst();
      if (latest.isEmpty()) {
        current.add(
            new ConsentsView.Current(purpose, "NONE", null, purpose.allowedWithoutRecord()));
      } else if (latest.get().inForce()) {
        current.add(new ConsentsView.Current(purpose, "GIVEN", latest.get().getGivenAt(), true));
      } else {
        current.add(
            new ConsentsView.Current(purpose, "WITHDRAWN", latest.get().getWithdrawnAt(), false));
      }
    }
    return new ConsentsView(current, rows.stream().map(CustomerConsentsImpl::view).toList());
  }

  static ConsentView view(CustomerConsent c) {
    return new ConsentView(
        c.getUid(),
        c.getPurpose(),
        c.getGivenAt(),
        c.getGivenBy(),
        c.getWithdrawnAt(),
        c.getWithdrawnBy(),
        c.getNote(),
        c.inForce());
  }

  private Customer customer(UUID uid) {
    return customers
        .findByUid(uid)
        .orElseThrow(() -> Refusal.notFound("customer_not_found", "No such customer."));
  }
}
