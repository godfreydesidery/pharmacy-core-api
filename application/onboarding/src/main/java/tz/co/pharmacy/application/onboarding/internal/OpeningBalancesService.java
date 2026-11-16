package tz.co.pharmacy.application.onboarding.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.application.onboarding.ports.inbound.OpeningBalances;
import tz.co.pharmacy.modules.finance.ports.inbound.Ledger;
import tz.co.pharmacy.modules.finance.ports.inbound.OpeningBalanceView;
import tz.co.pharmacy.modules.finance.ports.inbound.OpeningBalancesCommand;
import tz.co.pharmacy.modules.inventory.ports.inbound.StockQueries;
import tz.co.pharmacy.modules.partners.ports.inbound.CustomerService;
import tz.co.pharmacy.modules.partners.ports.inbound.SupplierService;
import tz.co.pharmacy.modules.receivables.ports.inbound.CustomerCredit;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.kernel.TenantCalendar;

@Service
class OpeningBalancesService implements OpeningBalances {

  /** The reference on each customer's opening charge, so a statement shows where it came from. */
  static final String REFERENCE = "OPENING";

  private final Ledger ledger;
  private final CustomerCredit credit;
  private final CustomerService customers;
  private final SupplierService suppliers;
  private final StockQueries stock;
  private final TenantCalendar calendar;

  OpeningBalancesService(
      Ledger ledger,
      CustomerCredit credit,
      CustomerService customers,
      SupplierService suppliers,
      StockQueries stock,
      TenantCalendar calendar) {
    this.calendar = calendar;
    this.ledger = ledger;
    this.credit = credit;
    this.customers = customers;
    this.suppliers = suppliers;
    this.stock = stock;
  }

  @Override
  @Transactional
  public OpeningBalanceView post(Command command) {
    Checks.required(command, "openingBalances");
    LocalDate asOf = command.asOf() == null ? calendar.today() : command.asOf();
    List<OpeningBalancesCommand.Party> receivables = new ArrayList<>();
    List<Amount> owed = command.receivables() == null ? List.of() : command.receivables();
    for (int i = 0; i < owed.size(); i++) {
      Amount line = owed.get(i);
      String field = "receivables[" + i + "].partyUid";
      var customer =
          customers
              .find(Checks.required(line.partyUid(), field))
              .orElseThrow(
                  () -> Refusal.notFound("customer_not_found", "No such customer.").about(field));
      receivables.add(
          new OpeningBalancesCommand.Party(customer.uid(), customer.name(), line.amount()));
    }
    List<OpeningBalancesCommand.Party> payables = new ArrayList<>();
    List<Amount> owing = command.payables() == null ? List.of() : command.payables();
    for (int i = 0; i < owing.size(); i++) {
      Amount line = owing.get(i);
      String field = "payables[" + i + "].partyUid";
      var supplier =
          suppliers
              .find(Checks.required(line.partyUid(), field))
              .orElseThrow(
                  () -> Refusal.notFound("supplier_not_found", "No such supplier.").about(field));
      payables.add(
          new OpeningBalancesCommand.Party(supplier.uid(), supplier.name(), line.amount()));
    }
    BigDecimal inventory = command.inventory() == null ? stockAtCost() : command.inventory();
    OpeningBalanceView posted =
        ledger.postOpening(
            new OpeningBalancesCommand(
                asOf,
                command.cash(),
                command.bank(),
                command.mobileMoney(),
                inventory,
                receivables,
                payables));
    // Each customer's account opens with what they owe, due on their usual terms.
    for (OpeningBalancesCommand.Party customer : receivables) {
      credit.charge(customer.uid(), null, REFERENCE, customer.amount(), asOf);
    }
    return posted;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<OpeningBalanceView> current() {
    return ledger.opening();
  }

  @Override
  @Transactional(readOnly = true)
  public BigDecimal stockAtCost() {
    BigDecimal value = stock.summary(null, 0).costValue();
    return value == null ? BigDecimal.ZERO : value;
  }
}
