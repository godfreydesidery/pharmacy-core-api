package tz.co.pharmacy.modules.partners.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.partners.domain.Customer;
import tz.co.pharmacy.modules.partners.domain.DataSubjectRequest;
import tz.co.pharmacy.modules.partners.persistence.CustomerRepository;
import tz.co.pharmacy.modules.partners.persistence.DataSubjectRequestRepository;
import tz.co.pharmacy.modules.partners.ports.inbound.DataSubjectRequestView;
import tz.co.pharmacy.modules.partners.ports.inbound.DataSubjectRequests;
import tz.co.pharmacy.modules.partners.ports.inbound.RequestKind;
import tz.co.pharmacy.modules.partners.ports.inbound.RequestStatus;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.kernel.TenantCalendar;
import tz.co.pharmacy.platform.persistence.DocumentNumbers;
import tz.co.pharmacy.platform.persistence.Keyset;
import tz.co.pharmacy.platform.persistence.Specs;

/** The request register, numbered DSR-000001 per pharmacy. */
@Service
@RequiredArgsConstructor
class DataSubjectRequestsImpl implements DataSubjectRequests {

  private final DataSubjectRequestRepository requests;
  private final CustomerRepository customers;
  private final DocumentNumbers numbers;
  private final TenantCalendar calendar;
  private final Clock clock;

  @Override
  @Transactional
  public DataSubjectRequestView log(
      RequestKind kind, UUID customerUid, LocalDate receivedOn, String note) {
    Checks.required(kind, "kind");
    Customer customer = customerFor(customerUid);
    LocalDate today = calendar.today();
    if (receivedOn != null && receivedOn.isAfter(today)) {
      throw Refusal.invalid(
          "invalid_received_on", "A request cannot be received after today.", "receivedOn");
    }
    Instant received =
        receivedOn == null || receivedOn.equals(today)
            ? clock.instant()
            : receivedOn.atStartOfDay(calendar.zone()).toInstant();
    DataSubjectRequest request =
        requests.save(
            new DataSubjectRequest(
                numbers.format("DSR", 6),
                kind,
                customer.getUid(),
                received,
                LocalDate.ofInstant(received, calendar.zone()).plusDays(DUE_DAYS),
                Checks.optionalText(note, "note", 1000)));
    return view(request, customer);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<DataSubjectRequestView> list(
      RequestStatus status, RequestKind kind, UUID customerUid, PageQuery query) {
    Page<DataSubjectRequest> page =
        Keyset.page(
            requests,
            Specs.<DataSubjectRequest>equal("status", status)
                .and(Specs.equal("kind", kind))
                .and(Specs.equal("customerUid", customerUid)),
            query,
            Function.identity());
    Set<UUID> uids =
        page.items().stream().map(DataSubjectRequest::getCustomerUid).collect(Collectors.toSet());
    Map<UUID, Customer> byUid =
        uids.isEmpty()
            ? Map.of()
            : customers.findByUidIn(uids).stream()
                .collect(Collectors.toMap(Customer::getUid, Function.identity()));
    return page.map(request -> view(request, byUid.get(request.getCustomerUid())));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<DataSubjectRequestView> find(UUID uid) {
    return requests.findByUid(uid).map(this::view);
  }

  @Override
  @Transactional(readOnly = true)
  public void requireOpen(UUID requestUid, RequestKind kind, UUID customerUid) {
    if (requestUid != null) {
      open(requestUid, kind, customerUid);
    }
  }

  @Override
  @Transactional
  public DataSubjectRequestView complete(
      UUID requestUid, RequestKind kind, UUID customerUid, String outcome) {
    DataSubjectRequest request;
    if (requestUid == null) {
      Customer customer = customerFor(customerUid);
      Instant now = clock.instant();
      request =
          requests.save(
              new DataSubjectRequest(
                  numbers.format("DSR", 6),
                  kind,
                  customer.getUid(),
                  now,
                  LocalDate.ofInstant(now, calendar.zone()).plusDays(DUE_DAYS),
                  null));
    } else {
      request = open(requestUid, kind, customerUid);
    }
    close(request, RequestStatus.COMPLETED, outcome);
    return view(request);
  }

  @Override
  @Transactional
  public DataSubjectRequestView complete(UUID requestUid, String outcome) {
    DataSubjectRequest request = open(requestUid, null, null);
    close(request, RequestStatus.COMPLETED, Checks.text(outcome, "outcome", 1000));
    return view(request);
  }

  @Override
  @Transactional
  public DataSubjectRequestView refuse(UUID requestUid, String reason) {
    DataSubjectRequest request = open(requestUid, null, null);
    close(request, RequestStatus.REFUSED, Checks.text(reason, "reason", 1000));
    return view(request);
  }

  private void close(DataSubjectRequest request, RequestStatus status, String outcome) {
    request.close(
        status,
        clock.instant(),
        ExecutionContext.actor().name(),
        ExecutionContext.actor().uid(),
        outcome);
    requests.flush();
  }

  private DataSubjectRequest open(UUID requestUid, RequestKind kind, UUID customerUid) {
    DataSubjectRequest request =
        requests
            .findByUid(requestUid)
            .orElseThrow(() -> Refusal.notFound("request_not_found", "No such request."));
    if (request.getStatus() != RequestStatus.OPEN) {
      throw Refusal.conflict(
          "request_closed",
          "Request " + request.getNumber() + " is already " + request.getStatus() + ".");
    }
    if ((kind != null && request.getKind() != kind)
        || (customerUid != null && !customerUid.equals(request.getCustomerUid()))) {
      throw Refusal.invalid(
              "request_mismatch",
              "Request "
                  + request.getNumber()
                  + " is a "
                  + request.getKind()
                  + " request about another customer or right.",
              "requestUid")
          .with("kind", request.getKind().name());
    }
    return request;
  }

  private Customer customerFor(UUID customerUid) {
    Checks.required(customerUid, "customerUid");
    return customers
        .findByUid(customerUid)
        .orElseThrow(
            () -> Refusal.invalid("customer_not_found", "No such customer.", "customerUid"));
  }

  private DataSubjectRequestView view(DataSubjectRequest request) {
    return view(request, customers.findByUid(request.getCustomerUid()).orElse(null));
  }

  private DataSubjectRequestView view(DataSubjectRequest r, Customer customer) {
    boolean overdue = r.getStatus() == RequestStatus.OPEN && calendar.today().isAfter(r.getDueOn());
    return new DataSubjectRequestView(
        r.getUid(),
        r.getNumber(),
        r.getKind(),
        r.getStatus(),
        r.getCustomerUid(),
        customer == null ? null : customer.getCode(),
        customer == null ? null : customer.getName(),
        r.getReceivedAt(),
        r.getDueOn(),
        r.getCompletedAt(),
        r.getCompletedBy(),
        r.getOutcome(),
        r.getNote(),
        overdue,
        r.getCreatedAt(),
        r.getCreatedBy());
  }
}
