package tz.co.pharmacy.delivery.api.v1.partners;

import java.math.BigDecimal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.delivery.api.Pages;
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
    return ApiResponse.created(customers.create(body.toCommand()), "Customer created");
  }

  @GetMapping("/customers/{uid}")
  @Access(scope = AccessScope.TENANT, permission = "partners.view")
  public ApiResponse<CustomerView> customer(@PathVariable UUID uid) {
    return ApiResponse.ok(
        customers
            .find(uid)
            .orElseThrow(() -> Refusal.notFound("customer_not_found", "No such customer.")),
        "Customer retrieved");
  }

  @PutMapping("/customers/{uid}")
  @Access(scope = AccessScope.TENANT, permission = "partners.manage")
  public ApiResponse<CustomerView> changeCustomer(
      @PathVariable UUID uid, @RequestBody CustomerRequest body) {
    return ApiResponse.ok(customers.change(uid, body.toCommand()), "Customer changed");
  }

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
  public ApiResponse<SupplierView> changeSupplier(
      @PathVariable UUID uid, @RequestBody SupplierRequest body) {
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

    CustomerCommand toCommand() {
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
