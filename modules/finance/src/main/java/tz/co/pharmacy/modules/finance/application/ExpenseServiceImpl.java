package tz.co.pharmacy.modules.finance.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.finance.domain.Expense;
import tz.co.pharmacy.modules.finance.events.ExpenseRecorded;
import tz.co.pharmacy.modules.finance.persistence.ExpenseRepository;
import tz.co.pharmacy.modules.finance.ports.inbound.ExpenseCategory;
import tz.co.pharmacy.modules.finance.ports.inbound.ExpenseCommand;
import tz.co.pharmacy.modules.finance.ports.inbound.ExpenseService;
import tz.co.pharmacy.modules.finance.ports.inbound.ExpenseView;
import tz.co.pharmacy.platform.bus.DomainEventPublisher;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.kernel.TenantCalendar;
import tz.co.pharmacy.platform.persistence.DocumentNumbers;
import tz.co.pharmacy.platform.persistence.Keyset;
import tz.co.pharmacy.platform.persistence.Specs;

@Service
class ExpenseServiceImpl implements ExpenseService {

  private final ExpenseRepository expenses;
  private final JournalWriter writer;
  private final DocumentNumbers numbers;
  private final DomainEventPublisher events;
  private final TenantCalendar calendar;

  ExpenseServiceImpl(
      ExpenseRepository expenses,
      JournalWriter writer,
      DocumentNumbers numbers,
      DomainEventPublisher events,
      TenantCalendar calendar) {
    this.calendar = calendar;
    this.expenses = expenses;
    this.writer = writer;
    this.numbers = numbers;
    this.events = events;
  }

  @Override
  @Transactional
  public ExpenseView record(ExpenseCommand command) {
    UUID branchUid = ExecutionContext.requireBranch();
    ExpenseCategory category = Checks.required(command.category(), "category");
    String description = Checks.text(command.description(), "description", 500);
    BigDecimal amount = Checks.notNegative(command.amount(), "amount");
    if (amount.signum() == 0) {
      throw Refusal.invalid("invalid_amount", "amount must be more than zero.", "amount");
    }
    LocalDate date = notFuture(command.expenseDate(), calendar.today(), "expenseDate");
    PaidFrom paidFrom = PaidFrom.parse(command.paymentMethod(), PaidFrom.CASH, "paymentMethod");
    String method = paidFrom.name();
    String reference = Checks.optionalText(command.reference(), "reference", 120);

    Expense expense =
        expenses.save(
            new Expense(
                numbers.format("EXP", 6),
                branchUid,
                category,
                description,
                amount,
                date,
                method,
                reference));
    writer.post(
        LedgerImpl.EXPENSE,
        expense.getUid(),
        expense.getNumber(),
        "Expense " + expense.getNumber() + ": " + description,
        branchUid,
        date,
        Postings.expense(category, amount, paidFrom));
    events.publish(
        "expense.recorded",
        metadata ->
            new ExpenseRecorded(
                metadata,
                expense.getUid(),
                expense.getNumber(),
                branchUid,
                category.name(),
                amount,
                date));
    return toView(expense);
  }

  /**
   * The date a payment was made: today in the pharmacy's time zone when not given, and never after
   * it (422 future_date); money paid tomorrow is not in today's books.
   */
  static LocalDate notFuture(LocalDate date, LocalDate today, String field) {
    if (date == null) {
      return today;
    }
    if (date.isAfter(today)) {
      throw Refusal.invalid(
              "future_date", "The date " + date + " is after today (" + today + ").", field)
          .with("date", date)
          .with("today", today);
    }
    return date;
  }

  @Override
  @Transactional(readOnly = true)
  public Page<ExpenseView> list(
      ExpenseCategory category, LocalDate from, LocalDate to, PageQuery query) {
    Specification<Expense> filter =
        Specs.<Expense>equal("category", category)
            .and(Specs.atLeast("expenseDate", from))
            .and(Specs.before("expenseDate", to == null ? null : to.plusDays(1)))
            .and(Specs.in("branchUid", ExecutionContext.reportBranches()));
    return Keyset.page(expenses, filter, query, ExpenseServiceImpl::toView);
  }

  static ExpenseView toView(Expense expense) {
    return new ExpenseView(
        expense.getUid(),
        expense.getNumber(),
        expense.getBranchUid(),
        expense.getCategory(),
        expense.getDescription(),
        expense.getAmount(),
        expense.getExpenseDate(),
        expense.getPaymentMethod(),
        expense.getReference(),
        expense.getCreatedAt(),
        expense.getCreatedBy());
  }
}
