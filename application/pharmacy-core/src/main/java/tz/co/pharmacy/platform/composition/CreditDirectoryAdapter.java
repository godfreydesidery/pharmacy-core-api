package tz.co.pharmacy.platform.composition;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.fx.ports.inbound.ExchangeRates;
import tz.co.pharmacy.modules.insurance.ports.outbound.InsuranceDirectory;
import tz.co.pharmacy.modules.partners.ports.inbound.CustomerService;
import tz.co.pharmacy.modules.receivables.ports.outbound.CustomerDirectory;
import tz.co.pharmacy.modules.receivables.ports.outbound.ReceivablesCurrency;
import tz.co.pharmacy.modules.tenancy.ports.inbound.SettingsService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantService;
import tz.co.pharmacy.platform.persistence.TenantContext;

/**
 * Binds what receivables and insurance read from other modules: the customer's name and credit
 * limit (partners), the base currency and the day's rates (tenancy, fx), and the pharmacy's name.
 * An account is due after the customer's payment terms, or after {@code
 * pharmacy.receivables.default-terms-days} (30) for a customer without any.
 */
@Component
class CreditDirectoryAdapter implements CustomerDirectory, ReceivablesCurrency, InsuranceDirectory {

  private final CustomerService customers;
  private final SettingsService settings;
  private final TenantService tenants;
  private final ExchangeRates rates;
  private final int termsDays;

  CreditDirectoryAdapter(
      CustomerService customers,
      SettingsService settings,
      TenantService tenants,
      ExchangeRates rates,
      @Value("${pharmacy.receivables.default-terms-days:30}") int termsDays) {
    this.customers = customers;
    this.settings = settings;
    this.tenants = tenants;
    this.rates = rates;
    this.termsDays = termsDays;
  }

  @Override
  public Optional<CustomerFacts> customer(UUID customerUid) {
    return customers
        .find(customerUid)
        .map(
            customer ->
                new CustomerFacts(
                    customer.name(),
                    customer.creditLimit() == null ? BigDecimal.ZERO : customer.creditLimit(),
                    termsDays(customer.paymentTerms()),
                    customer.active()));
  }

  /** The customer's own terms; the configured default when none is recorded. */
  private int termsDays(tz.co.pharmacy.modules.partners.ports.inbound.PaymentTerms terms) {
    if (terms == null) {
      return termsDays;
    }
    return switch (terms) {
      case COD -> 0;
      case NET_15 -> 15;
      case NET_30 -> 30;
      case NET_45 -> 45;
      case NET_60 -> 60;
    };
  }

  @Override
  public Optional<String> customerName(UUID customerUid) {
    return customers.find(customerUid).map(customer -> customer.name());
  }

  @Override
  public String baseCurrency() {
    return settings.current().currency();
  }

  @Override
  public BigDecimal rateToBase(String currency, LocalDate on) {
    return rates.rate(currency, baseCurrency(), on);
  }

  @Override
  public String currency() {
    return baseCurrency();
  }

  @Override
  public String providerName() {
    return TenantContext.tenantUid()
        .flatMap(tenants::find)
        .map(tenant -> tenant.name())
        .orElse("Pharmacy");
  }
}
