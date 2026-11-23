package tz.co.pharmacy.modules.receivables.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.receivables.domain.Allocation;
import tz.co.pharmacy.modules.receivables.domain.Allocator;
import tz.co.pharmacy.modules.receivables.domain.CreditAccount;
import tz.co.pharmacy.modules.receivables.domain.Entry;
import tz.co.pharmacy.modules.receivables.events.CreditNoteIssued;
import tz.co.pharmacy.modules.receivables.events.DebtWrittenOff;
import tz.co.pharmacy.modules.receivables.events.PaymentReceived;
import tz.co.pharmacy.modules.receivables.persistence.AllocationRepository;
import tz.co.pharmacy.modules.receivables.persistence.CreditAccountRepository;
import tz.co.pharmacy.modules.receivables.persistence.EntryRepository;
import tz.co.pharmacy.modules.receivables.ports.inbound.AccountView;
import tz.co.pharmacy.modules.receivables.ports.inbound.AgeingRow;
import tz.co.pharmacy.modules.receivables.ports.inbound.AllocationView;
import tz.co.pharmacy.modules.receivables.ports.inbound.CustomerCredit;
import tz.co.pharmacy.modules.receivables.ports.inbound.EntryType;
import tz.co.pharmacy.modules.receivables.ports.inbound.EntryView;
import tz.co.pharmacy.modules.receivables.ports.inbound.ReceivableTotals;
import tz.co.pharmacy.modules.receivables.ports.inbound.ReceivePaymentCommand;
import tz.co.pharmacy.modules.receivables.ports.outbound.CustomerDirectory;
import tz.co.pharmacy.modules.receivables.ports.outbound.CustomerDirectory.CustomerFacts;
import tz.co.pharmacy.modules.receivables.ports.outbound.ReceivablesCurrency;
import tz.co.pharmacy.platform.approval.ApprovalActions;
import tz.co.pharmacy.platform.approval.Approvals;
import tz.co.pharmacy.platform.bus.DomainEventPublisher;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.kernel.TenantCalendar;
import tz.co.pharmacy.platform.persistence.DocumentNumbers;

/**
 * Customer accounts. Every movement is an entry in the base currency; credits settle debits through
 * allocations, so a statement shows exactly which payment cleared which invoice, and what is left
 * of a payment stays on the account as credit the customer may spend later.
 */
@Service
class CustomerCreditImpl implements CustomerCredit {

  private final CreditAccountRepository accounts;
  private final EntryRepository entries;
  private final AllocationRepository allocations;
  private final CustomerDirectory customers;
  private final ReceivablesCurrency currency;
  private final DocumentNumbers numbers;
  private final DomainEventPublisher events;
  private final Clock clock;
  private final JdbcTemplate jdbc;
  private final TenantCalendar calendar;
  private final Approvals approvals;

  CustomerCreditImpl(
      CreditAccountRepository accounts,
      EntryRepository entries,
      AllocationRepository allocations,
      CustomerDirectory customers,
      ReceivablesCurrency currency,
      DocumentNumbers numbers,
      DomainEventPublisher events,
      Clock clock,
      DataSource dataSource,
      TenantCalendar calendar,
      Approvals approvals) {
    this.calendar = calendar;
    this.approvals = approvals;
    this.accounts = accounts;
    this.entries = entries;
    this.allocations = allocations;
    this.customers = customers;
    this.currency = currency;
    this.numbers = numbers;
    this.events = events;
    this.clock = clock;
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  @Transactional
  public AccountView account(UUID customerUid) {
    return view(accountOf(customerUid));
  }

  @Override
  @Transactional
  public void checkCredit(UUID customerUid, BigDecimal amount) {
    // Held until the caller's transaction ends, so the check and the charge that follows it are
    // one step: two tills cannot both see room under the limit and both charge.
    accounts.lockCredit(Checks.required(customerUid, "customerUid"));
    CreditAccount account = accountOf(customerUid);
    if (account.isOnHold()) {
      throw Refusal.invalid(
              "account_on_hold",
              account.getCustomerName() + "'s account is on hold; no new credit is allowed.",
              "customerUid")
          .with("customer", account.getCustomerName());
    }
    BigDecimal balance = balance(customerUid);
    BigDecimal after = balance.add(Amounts.money(amount));
    if (after.compareTo(account.getCreditLimit()) > 0) {
      String code = currency.baseCurrency();
      throw Refusal.invalid(
              "credit_limit_exceeded",
              "This would take "
                  + account.getCustomerName()
                  + " to "
                  + Amounts.display(code, after)
                  + ", over the credit limit of "
                  + Amounts.display(code, account.getCreditLimit())
                  + " (available "
                  + Amounts.display(
                      code, account.getCreditLimit().subtract(balance).max(BigDecimal.ZERO))
                  + ").",
              "payments")
          .with("customer", account.getCustomerName())
          .with("limit", account.getCreditLimit())
          .with("available", account.getCreditLimit().subtract(balance).max(BigDecimal.ZERO))
          .with("currency", code);
    }
  }

  @Override
  @Transactional
  public EntryView charge(
      UUID customerUid, UUID saleUid, String saleNumber, BigDecimal amount, LocalDate date) {
    CreditAccount account = accountOf(customerUid);
    BigDecimal value = positive(amount, "amount");
    LocalDate on = date == null ? today() : date;
    // Idempotent per (customer, sale, reference): a redelivered event charges nothing new.
    var earlier =
        entries.findFirstByCustomerUidAndTypeAndSaleUidAndReference(
            customerUid, EntryType.CHARGE, saleUid, saleNumber);
    if (earlier.isPresent()) {
      return view(earlier.get(), List.of());
    }
    Entry charge =
        entries.save(
            Entry.debit(
                customerUid,
                EntryType.CHARGE,
                saleNumber,
                saleUid,
                value,
                true,
                on,
                on.plusDays(account.getTermsDays()),
                null));
    return view(charge, List.of());
  }

  @Override
  @Transactional
  public EntryView applyCredit(
      UUID customerUid, UUID saleUid, String saleNumber, BigDecimal amount) {
    accountOf(customerUid);
    BigDecimal value = positive(amount, "amount");
    List<Entry> credits = entries.openCredits(customerUid);
    BigDecimal available =
        credits.stream().map(Entry::getOpenAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    if (available.compareTo(value) < 0) {
      throw Refusal.invalid(
          "insufficient_account_credit",
          "The account holds "
              + available.toPlainString()
              + " of credit, less than "
              + value.toPlainString()
              + ".",
          "payments");
    }
    Entry spend =
        entries.save(
            Entry.debit(
                customerUid,
                EntryType.CREDIT_APPLIED,
                saleNumber,
                saleUid,
                value,
                true,
                today(),
                null,
                "Account credit used at the till"));
    List<Allocation> made = allocations.saveAll(Allocator.settle(spend, credits));
    return view(spend, made);
  }

  @Override
  @Transactional
  public EntryView receivePayment(ReceivePaymentCommand c) {
    UUID customerUid = Checks.required(c.customerUid(), "customerUid");
    accountOf(customerUid);
    BigDecimal paid = positive(c.amount(), "amount");
    String method =
        c.method() == null || c.method().isBlank() ? "CASH" : c.method().trim().toUpperCase();
    LocalDate date = c.date() == null ? today() : c.date();
    String base = currency.baseCurrency();
    String paidIn =
        c.currency() == null || c.currency().isBlank() ? base : c.currency().trim().toUpperCase();
    Conversion conversion = convert(paid, paidIn, base, c.exchangeRate(), date);

    String number = numbers.format("RCP", 6);
    String reference = Checks.optionalText(c.reference(), "reference", 100);
    Entry payment =
        Entry.credit(
                customerUid, EntryType.PAYMENT, number, null, conversion.base(), date, reference)
            .paidIn(
                method,
                paidIn.equals(base) ? null : paidIn,
                paidIn.equals(base) ? null : paid,
                paidIn.equals(base) ? null : conversion.rate());
    payment = entries.save(payment);
    List<Allocation> made =
        allocations.saveAll(Allocator.allocate(payment, chosenDebits(customerUid, c.allocateTo())));
    Entry saved = payment;
    events.publish(
        "receivable.payment_received",
        meta ->
            new PaymentReceived(
                meta,
                customerUid,
                conversion.base(),
                method,
                number,
                saved.getUid(),
                saved.getCurrency(),
                saved.getForeignAmount(),
                saved.getExchangeRate(),
                conversion.difference()));
    return view(payment, made);
  }

  @Override
  @Transactional
  public EntryView creditNote(
      UUID customerUid, UUID saleUid, String reference, BigDecimal amount, String refundMethod) {
    accountOf(customerUid);
    BigDecimal value = positive(amount, "amount");
    Entry note =
        entries.save(
            Entry.credit(
                customerUid,
                EntryType.CREDIT_NOTE,
                reference,
                saleUid,
                value,
                today(),
                "Return " + reference));
    List<Entry> debits = new ArrayList<>(entries.openDebits(customerUid));
    // The returned sale's own charge first, then nothing else: what is left is account credit.
    debits.removeIf(debit -> !saleUid.equals(debit.getSaleUid()));
    List<Allocation> made = allocations.saveAll(Allocator.allocate(note, debits));
    events.publish(
        "receivable.credit_note_issued",
        meta ->
            new CreditNoteIssued(
                meta, customerUid, note.getUid(), saleUid, reference, value, refundMethod));
    return view(note, made);
  }

  @Override
  @Transactional(readOnly = true)
  public BigDecimal openForSale(UUID customerUid, UUID saleUid) {
    return Amounts.money(entries.openForSale(customerUid, saleUid));
  }

  @Override
  @Transactional
  public EntryView writeOff(UUID customerUid, BigDecimal amount, String reason) {
    CreditAccount account = accountOf(customerUid);
    BigDecimal value = positive(amount, "amount");
    String why = Checks.text(reason, "reason", 500);
    List<Entry> debits = entries.openDebits(customerUid);
    BigDecimal open =
        debits.stream().map(Entry::getOpenAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    if (value.compareTo(open) > 0) {
      throw Refusal.invalid(
          "write_off_exceeds_open",
          "Only " + open.toPlainString() + " is open on this account.",
          "amount");
    }
    // Writing off a debt is forgiving money: a second person approves it, whatever the amount
    // (the owner's rules of 23 Nov 2026; adversarial review FRD-17).
    approvals.require(ApprovalActions.CREDIT_WRITE_OFF, value, account.getCustomerName());
    String number = numbers.format("WO", 6);
    Entry writeOff =
        entries.save(
            Entry.credit(customerUid, EntryType.WRITE_OFF, number, null, value, today(), why));
    List<Allocation> made = allocations.saveAll(Allocator.allocate(writeOff, debits));
    events.publish(
        "receivable.written_off",
        meta -> new DebtWrittenOff(meta, customerUid, writeOff.getUid(), number, value, why));
    return view(writeOff, made);
  }

  @Override
  @Transactional
  public AccountView hold(UUID customerUid, boolean onHold, String reason) {
    CreditAccount account = accountOf(customerUid);
    account.hold(onHold, onHold ? Checks.text(reason, "reason", 500) : null);
    return view(account);
  }

  /**
   * One page of the period's entries (PERF-17): the period's entries in date order are numbered in
   * SQL, with each one's running sum from the period's start, and only the page's rows are loaded.
   * The balance brought forward comes from the daily balances (one row per day with entries), not
   * from every earlier entry. Without dates, the last 90 days up to today.
   */
  @Override
  @Transactional(readOnly = true)
  public Page<EntryView> statement(
      UUID customerUid, LocalDate from, LocalDate to, PageQuery query) {
    LocalDate end = to == null ? today() : to;
    LocalDate start = from == null ? end.minusDays(STATEMENT_DAYS - 1L) : from;
    if (start.isAfter(end)) {
      throw Refusal.invalid("invalid_period", "from must not be after to.", "from");
    }
    BigDecimal opening = openingBalance(customerUid, start);
    Map<Long, BigDecimal> runningById = new LinkedHashMap<>();
    jdbc.query(
        "select id, sum(amount) over (order by entry_date, id) as running"
            + " from receivables.entries"
            + " where customer_uid = ? and entry_date >= ? and entry_date <= ?"
            + " order by entry_date, id limit ? offset ?",
        row -> {
          runningById.put(row.getLong("id"), row.getBigDecimal("running"));
        },
        customerUid,
        start,
        end,
        query.limit() + 1,
        query.offset());
    Map<Long, Entry> byId = new HashMap<>();
    entries.findAllById(runningById.keySet()).forEach(entry -> byId.put(entry.getId(), entry));
    List<Entry> rows = runningById.keySet().stream().map(byId::get).toList();
    Map<Long, List<Allocation>> touching = touching(rows);
    List<EntryView> views = new ArrayList<>();
    for (Entry entry : rows) {
      views.add(
          withRunning(
              view(entry, touching.getOrDefault(entry.getId(), List.of())),
              opening.add(runningById.get(entry.getId()))));
    }
    return Page.offset(
        views,
        query,
        () -> {
          Long total =
              jdbc.queryForObject(
                  "select count(*) from receivables.entries"
                      + " where customer_uid = ? and entry_date >= ? and entry_date <= ?",
                  Long.class,
                  customerUid,
                  start,
                  end);
          return total == null ? 0 : total;
        });
  }

  /** What the customer owed before {@code before}: the daily balances of the days before it. */
  @Override
  @Transactional(readOnly = true)
  public BigDecimal openingBalance(UUID customerUid, LocalDate before) {
    BigDecimal balance =
        jdbc.queryForObject(
            "select coalesce(sum(amount), 0) from receivables.customer_daily_balances"
                + " where customer_uid = ? and day < ?",
            BigDecimal.class,
            customerUid,
            before);
    return Amounts.money(balance == null ? BigDecimal.ZERO : balance);
  }

  @Override
  @Transactional(readOnly = true)
  public List<AgeingRow> ageing(LocalDate asOf) {
    LocalDate on = asOf == null ? today() : asOf;
    return jdbc.query(
        "select a.customer_uid, a.customer_name,"
            + " coalesce(sum(case when ? - e.entry_date <= 30 then e.open_amount end), 0) as current,"
            + " coalesce(sum(case when ? - e.entry_date between 31 and 60 then e.open_amount end), 0) as d31,"
            + " coalesce(sum(case when ? - e.entry_date between 61 and 90 then e.open_amount end), 0) as d61,"
            + " coalesce(sum(case when ? - e.entry_date > 90 then e.open_amount end), 0) as d90,"
            + " coalesce(sum(e.open_amount), 0) as total"
            + " from receivables.accounts a"
            + " join receivables.entries e on e.customer_uid = a.customer_uid"
            + " where e.amount > 0 and e.open_amount > 0 and e.entry_date <= ?"
            + " group by a.customer_uid, a.customer_name order by total desc",
        (row, i) ->
            new AgeingRow(
                row.getObject("customer_uid", UUID.class),
                row.getString("customer_name"),
                Amounts.money(row.getBigDecimal("current")),
                Amounts.money(row.getBigDecimal("d31")),
                Amounts.money(row.getBigDecimal("d61")),
                Amounts.money(row.getBigDecimal("d90")),
                Amounts.money(row.getBigDecimal("total"))),
        on,
        on,
        on,
        on,
        on);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<AccountView> accounts(String q, boolean withBalanceOnly, PageQuery query) {
    String pattern = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%";
    List<AccountView> rows =
        jdbc.query(
            "select a.customer_uid, a.customer_name, a.credit_limit, a.on_hold, a.hold_reason,"
                + " a.terms_days, coalesce(sum(e.amount), 0) as balance,"
                + " coalesce(sum(case when e.amount > 0 and e.open_amount > 0 and e.due_date < ?"
                + "   then e.open_amount end), 0) as overdue,"
                + " min(case when e.amount > 0 and e.open_amount > 0 then e.entry_date end) as oldest,"
                + " coalesce(sum(case when e.amount < 0 then e.open_amount end), 0) as unallocated"
                + " from receivables.accounts a"
                + " left join receivables.entries e on e.customer_uid = a.customer_uid"
                + " where (cast(? as text) is null or lower(a.customer_name) like cast(? as text))"
                + " group by a.id, a.customer_uid, a.customer_name, a.credit_limit, a.on_hold,"
                + " a.hold_reason, a.terms_days"
                + (withBalanceOnly ? " having coalesce(sum(e.amount), 0) <> 0" : "")
                + " order by lower(a.customer_name), a.id limit ? offset ?",
            (row, i) -> {
              BigDecimal limit = Amounts.money(row.getBigDecimal("credit_limit"));
              BigDecimal balance = Amounts.money(row.getBigDecimal("balance"));
              var oldest = row.getDate("oldest");
              return new AccountView(
                  row.getObject("customer_uid", UUID.class),
                  row.getString("customer_name"),
                  limit,
                  balance,
                  Amounts.money(limit.subtract(balance)),
                  Amounts.money(row.getBigDecimal("overdue")),
                  oldest == null ? null : oldest.toLocalDate(),
                  row.getBoolean("on_hold"),
                  row.getString("hold_reason"),
                  Amounts.money(row.getBigDecimal("unallocated")),
                  row.getInt("terms_days"));
            },
            today(),
            pattern,
            pattern,
            query.limit() + 1,
            query.offset());
    return Page.offset(
        rows,
        query,
        () -> {
          // The accounts the same filter keeps, for a numbered page's total.
          Long total =
              jdbc.queryForObject(
                  "select count(*) from (select a.id from receivables.accounts a"
                      + " left join receivables.entries e on e.customer_uid = a.customer_uid"
                      + " where (cast(? as text) is null or lower(a.customer_name) like cast(? as text))"
                      + " group by a.id"
                      + (withBalanceOnly ? " having coalesce(sum(e.amount), 0) <> 0" : "")
                      + ") counted",
                  Long.class,
                  pattern,
                  pattern);
          return total == null ? 0 : total;
        });
  }

  @Override
  @Transactional(readOnly = true)
  public ReceivableTotals totals(LocalDate asOf) {
    LocalDate on = asOf == null ? today() : asOf;
    return jdbc.queryForObject(
        "select coalesce(sum(case when amount > 0 then open_amount end), 0) as outstanding,"
            + " coalesce(sum(case when amount > 0 and due_date < ? then open_amount end), 0) as overdue,"
            + " coalesce(sum(case when amount < 0 then open_amount end), 0) as unallocated,"
            + " count(distinct case when amount > 0 and open_amount > 0 then customer_uid end) as accounts"
            + " from receivables.entries",
        (row, i) ->
            new ReceivableTotals(
                Amounts.money(row.getBigDecimal("outstanding")),
                Amounts.money(row.getBigDecimal("overdue")),
                Amounts.money(row.getBigDecimal("unallocated")),
                row.getLong("accounts")),
        on);
  }

  // ---------------------------------------------------------------------------------------------

  /**
   * The base amount of a payment, the rate used and the rounding left over (to FX gains/losses).
   */
  record Conversion(BigDecimal base, BigDecimal rate, BigDecimal difference) {}

  Conversion convert(
      BigDecimal paid, String paidIn, String base, BigDecimal givenRate, LocalDate on) {
    if (paidIn.equals(base)) {
      return new Conversion(Amounts.money(paid), BigDecimal.ONE, Amounts.ZERO);
    }
    if (givenRate != null && givenRate.signum() <= 0) {
      throw Refusal.invalid(
          "invalid_exchange_rate", "exchangeRate must be more than zero.", "exchangeRate");
    }
    // The recorded rate of the day values the money: whoever takes the payment cannot set it. A
    // rate sent with the payment must agree with it to within 2%.
    BigDecimal rate = currency.rateToBase(paidIn, on);
    if (rate == null || rate.signum() <= 0) {
      throw Refusal.invalid(
          "invalid_exchange_rate", "No " + paidIn + " rate is recorded.", "exchangeRate");
    }
    if (givenRate != null
        && givenRate
                .subtract(rate)
                .abs()
                .divide(rate, 6, RoundingMode.HALF_EVEN)
                .compareTo(RATE_TOLERANCE)
            > 0) {
      throw Refusal.invalid(
          "exchange_rate_mismatch",
          "The "
              + paidIn
              + " rate on "
              + on
              + " is "
              + rate.stripTrailingZeros().toPlainString()
              + "; a different rate is recorded under Exchange rates first.",
          "exchangeRate");
    }
    return conversion(paid, rate);
  }

  static final BigDecimal RATE_TOLERANCE = new BigDecimal("0.02");

  /** Exact base value, rounded to cents; the sub-cent rounding is reported, never lost. */
  static Conversion conversion(BigDecimal paid, BigDecimal rate) {
    BigDecimal exact = paid.multiply(rate).setScale(6, RoundingMode.HALF_UP);
    BigDecimal rounded = Amounts.money(exact);
    return new Conversion(rounded, rate, exact.subtract(rounded).setScale(6, RoundingMode.HALF_UP));
  }

  private List<Entry> chosenDebits(UUID customerUid, List<UUID> allocateTo) {
    List<Entry> open = entries.openDebits(customerUid);
    if (allocateTo == null || allocateTo.isEmpty()) {
      return open;
    }
    Map<UUID, Entry> byUid = new HashMap<>();
    open.forEach(entry -> byUid.put(entry.getUid(), entry));
    List<Entry> chosen = new ArrayList<>();
    for (int i = 0; i < allocateTo.size(); i++) {
      Entry debit = byUid.get(allocateTo.get(i));
      if (debit == null) {
        throw Refusal.invalid(
            "charge_not_open", "That charge is not open on this account.", "allocateTo[" + i + "]");
      }
      chosen.add(debit);
    }
    return chosen;
  }

  private CreditAccount accountOf(UUID customerUid) {
    CustomerFacts facts =
        customers
            .customer(customerUid)
            .orElseThrow(
                () -> Refusal.invalid("customer_not_found", "No such customer.", "customerUid"));
    CreditAccount account =
        accounts
            .findByCustomerUid(customerUid)
            .orElseGet(
                () ->
                    accounts.save(
                        new CreditAccount(
                            customerUid, facts.name(), facts.creditLimit(), facts.termsDays())));
    account.refresh(facts.name(), facts.creditLimit(), facts.termsDays());
    return account;
  }

  private BigDecimal balance(UUID customerUid) {
    return Amounts.money(entries.balanceBefore(customerUid, LocalDate.of(9999, 12, 31)));
  }

  private AccountView view(CreditAccount account) {
    UUID customer = account.getCustomerUid();
    BigDecimal balance = balance(customer);
    List<Entry> debits = entries.openDebits(customer);
    LocalDate today = today();
    BigDecimal overdue =
        debits.stream()
            .filter(e -> e.getDueDate() != null && e.getDueDate().isBefore(today))
            .map(Entry::getOpenAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    LocalDate oldest = debits.isEmpty() ? null : debits.getFirst().getEntryDate();
    BigDecimal unallocated =
        entries.openCredits(customer).stream()
            .map(Entry::getOpenAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    return new AccountView(
        customer,
        account.getCustomerName(),
        account.getCreditLimit(),
        balance,
        Amounts.money(account.getCreditLimit().subtract(balance)),
        Amounts.money(overdue),
        oldest,
        account.isOnHold(),
        account.getHoldReason(),
        Amounts.money(unallocated),
        account.getTermsDays());
  }

  private Map<Long, List<Allocation>> touching(Collection<Entry> rows) {
    Map<Long, List<Allocation>> byEntry = new HashMap<>();
    if (rows.isEmpty()) {
      return byEntry;
    }
    for (Allocation allocation : allocations.touching(rows.stream().map(Entry::getId).toList())) {
      byEntry
          .computeIfAbsent(allocation.getCredit().getId(), k -> new ArrayList<>())
          .add(allocation);
      byEntry
          .computeIfAbsent(allocation.getDebit().getId(), k -> new ArrayList<>())
          .add(allocation);
    }
    return byEntry;
  }

  private static EntryView view(Entry entry, List<Allocation> made) {
    List<AllocationView> lines =
        made.stream()
            .map(
                allocation -> {
                  Entry other =
                      allocation.getCredit() == entry
                              || allocation.getCredit().getId() != null
                                  && allocation.getCredit().getId().equals(entry.getId())
                          ? allocation.getDebit()
                          : allocation.getCredit();
                  return new AllocationView(
                      other.getUid(),
                      other.getReference(),
                      other.getSaleUid(),
                      allocation.getAmount());
                })
            .toList();
    return new EntryView(
        entry.getUid(),
        entry.getType(),
        entry.getReference(),
        entry.getSaleUid(),
        entry.getAmount(),
        entry.getOpenAmount(),
        entry.getEntryDate(),
        entry.getDueDate(),
        entry.getNote(),
        entry.getCreatedAt(),
        entry.getCreatedBy(),
        entry.getMethod(),
        entry.getCurrency(),
        entry.getForeignAmount(),
        entry.getExchangeRate(),
        lines,
        null);
  }

  private static EntryView withRunning(EntryView v, BigDecimal running) {
    return new EntryView(
        v.uid(),
        v.type(),
        v.reference(),
        v.saleUid(),
        v.amount(),
        v.openAmount(),
        v.date(),
        v.dueDate(),
        v.note(),
        v.createdAt(),
        v.createdBy(),
        v.method(),
        v.currency(),
        v.foreignAmount(),
        v.exchangeRate(),
        v.allocations(),
        Amounts.money(running));
  }

  private static BigDecimal positive(BigDecimal amount, String field) {
    if (amount == null || amount.signum() <= 0) {
      throw Refusal.invalid("invalid_" + field, field + " must be more than zero.", field);
    }
    return Amounts.money(amount);
  }

  private LocalDate today() {
    return calendar.today();
  }
}
