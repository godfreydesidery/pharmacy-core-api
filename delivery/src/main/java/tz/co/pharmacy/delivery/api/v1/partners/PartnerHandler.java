package tz.co.pharmacy.delivery.api.v1.partners;

import java.math.BigDecimal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.delivery.api.Pages;
import tz.co.pharmacy.delivery.edge.Preconditions;
import tz.co.pharmacy.modules.partners.ports.inbound.CustomerCommand;
import tz.co.pharmacy.modules.partners.ports.inbound.CustomerService;
import tz.co.pharmacy.modules.partners.ports.inbound.CustomerType;
import tz.co.pharmacy.modules.partners.ports.inbound.CustomerView;
import tz.co.pharmacy.modules.partners.ports.inbound.PaymentTerms;
import tz.co.pharmacy.modules.partners.ports.inbound.SupplierCommand;
import tz.co.pharmacy.modules.partners.ports.inbound.SupplierService;
import tz.co.pharmacy.modules.partners.ports.inbound.SupplierView;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.Refusal;

/** Customers and suppliers of the tenant (contract §8). */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class PartnerHandler {

  private final CustomerService customers;
  private final SupplierService suppliers;
  private final Preconditions preconditions;

  // customers

  @GetMapping("/customers")
  @Access(scope = AccessScope.TENANT, permission = "partners.view")
  public ApiResponse<Page<CustomerView>> customers(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String type,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    CustomerType customerType = blank(type) ? null : Checks.oneOf(CustomerType.class, type, "type");
    return ApiResponse.ok(
        customers.search(q, customerType, Pages.of(cursor, limit)), "Customers retrieved");
  }

  @PostMapping("/customers")
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.TENANT, permission = "partners.manage")
  public ApiResponse<CustomerView> createCustomer(@RequestBody CustomerRequest body) {
    if (body == null) {
      throw Refusal.malformed("A customer is required.");
    }
    // A credit limit needs its own permission (FRD-17): 403 credit_limit_not_allowed.
    CreditLimits.require(BigDecimal.ZERO, body.creditLimit());
    return ApiResponse.created(
        customers.create(body.toCommand(body.creditLimit())), "Customer created");
  }

  /**
   * One customer, with its ETag; also for someone who only decides credit limits
   * (partners.credit_limit), whose change of the limit names the version it read.
   */
  @GetMapping("/customers/{uid}")
  @Access(
      scope = AccessScope.TENANT,
      anyOf = {"partners.view", CreditLimits.PERMISSION})
  public ApiResponse<CustomerView> customer(@PathVariable UUID uid) {
    return ApiResponse.ok(
        customers
            .find(uid)
            .orElseThrow(() -> Refusal.notFound("customer_not_found", "No such customer.")),
        "Customer retrieved");
  }

  /**
   * An omitted {@code creditLimit} keeps the customer's limit. Changing it needs
   * partners.credit_limit (403 credit_limit_not_allowed). One transaction: the customer's version
   * makes a change that crossed another one refuse (409 stale_version) rather than act on a stale
   * limit.
   */
  @PutMapping("/customers/{uid}")
  @Access(scope = AccessScope.TENANT, permission = "partners.manage")
  @Transactional
  public ApiResponse<CustomerView> changeCustomer(
      @PathVariable UUID uid,
      @RequestHeader(value = Preconditions.IF_MATCH, required = false) String ifMatch,
      @RequestBody CustomerRequest body) {
    if (body == null) {
      throw Refusal.malformed("A customer is required.");
    }
    CustomerView current =
        customers
            .find(uid)
            .orElseThrow(() -> Refusal.notFound("customer_not_found", "No such customer."));
    preconditions.ifMatch(ifMatch, current::version);
    BigDecimal limit = body.creditLimit() == null ? current.creditLimit() : body.creditLimit();
    CreditLimits.require(current.creditLimit(), limit);
    return ApiResponse.ok(customers.change(uid, body.toCommand(limit)), "Customer changed");
  }

  /**
   * Sets only the customer's credit limit, for someone who decides credit without managing
   * customers (the Accountant preset). Everything else about the customer stays as it is.
   */
  @PutMapping("/customers/{uid}/credit-limit")
  @Access(scope = AccessScope.TENANT, permission = CreditLimits.PERMISSION)
  @Transactional
  public ApiResponse<CustomerView> changeCreditLimit(
      @PathVariable UUID uid,
      @RequestHeader(value = Preconditions.IF_MATCH, required = false) String ifMatch,
      @RequestBody CreditLimitRequest body) {
    if (body == null || body.creditLimit() == null) {
      throw Refusal.invalid("invalid_credit_limit", "creditLimit is required.", "creditLimit");
    }
    CustomerView c =
        customers
            .find(uid)
            .orElseThrow(() -> Refusal.notFound("customer_not_found", "No such customer."));
    preconditions.ifMatch(ifMatch, c::version);
    return ApiResponse.ok(
        customers.change(
            uid,
            new CustomerCommand(
                c.name(),
                c.phone(),
                c.email(),
                c.type(),
                c.insuranceProvider(),
                c.insuranceNumber(),
                body.creditLimit(),
                c.address(),
                c.active(),
                c.paymentTerms())),
        "Credit limit changed");
  }

  public record CreditLimitRequest(BigDecimal creditLimit) {}

  // suppliers

  @GetMapping("/suppliers")
  @Access(scope = AccessScope.TENANT, permission = "partners.view")
  public ApiResponse<Page<SupplierView>> suppliers(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    return ApiResponse.ok(suppliers.search(q, Pages.of(cursor, limit)), "Suppliers retrieved");
  }

  @PostMapping("/suppliers")
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.TENANT, permission = "partners.manage")
  public ApiResponse<SupplierView> createSupplier(@RequestBody SupplierRequest body) {
    return ApiResponse.created(suppliers.create(body.toCommand()), "Supplier created");
  }

  @GetMapping("/suppliers/{uid}")
  @Access(scope = AccessScope.TENANT, permission = "partners.view")
  public ApiResponse<SupplierView> supplier(@PathVariable UUID uid) {
    return ApiResponse.ok(
        suppliers
            .find(uid)
            .orElseThrow(() -> Refusal.notFound("supplier_not_found", "No such supplier.")),
        "Supplier retrieved");
  }

  @PutMapping("/suppliers/{uid}")
  @Access(scope = AccessScope.TENANT, permission = "partners.manage")
  @Transactional
  public ApiResponse<SupplierView> changeSupplier(
      @PathVariable UUID uid,
      @RequestHeader(value = Preconditions.IF_MATCH, required = false) String ifMatch,
      @RequestBody SupplierRequest body) {
    if (body == null) {
      throw Refusal.malformed("A supplier is required.");
    }
    preconditions.ifMatch(
        ifMatch,
        () ->
            suppliers
                .find(uid)
                .orElseThrow(() -> Refusal.notFound("supplier_not_found", "No such supplier."))
                .version());
    return ApiResponse.ok(suppliers.change(uid, body.toCommand()), "Supplier changed");
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  public record CustomerRequest(
      String name,
      String phone,
      String email,
      String type,
      String insuranceProvider,
      String insuranceNumber,
      BigDecimal creditLimit,
      String address,
      Boolean active,
      String paymentTerms) {

    CustomerCommand toCommand(BigDecimal creditLimit) {
      return new CustomerCommand(
          name,
          phone,
          email,
          blank(type) ? null : Checks.oneOf(CustomerType.class, type, "type"),
          insuranceProvider,
          insuranceNumber,
          creditLimit,
          address,
          active,
          blank(paymentTerms)
              ? null
              : Checks.oneOf(PaymentTerms.class, paymentTerms, "paymentTerms"));
    }
  }

  public record SupplierRequest(
      String name,
      String contactPerson,
      String phone,
      String email,
      String tin,
      String paymentTerms,
      String address,
      Boolean active,
      Integer leadTimeDays) {

    SupplierCommand toCommand() {
      return new SupplierCommand(
          name,
          contactPerson,
          phone,
          email,
          tin,
          blank(paymentTerms)
              ? null
              : Checks.oneOf(PaymentTerms.class, paymentTerms, "paymentTerms"),
          address,
          active,
          leadTimeDays);
    }
  }
}
