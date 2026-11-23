package tz.co.pharmacy.delivery.api.v1.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.delivery.api.Idempotent;
import tz.co.pharmacy.delivery.api.Pages;
import tz.co.pharmacy.modules.finance.ports.inbound.ExpenseCategory;
import tz.co.pharmacy.modules.finance.ports.inbound.ExpenseCommand;
import tz.co.pharmacy.modules.finance.ports.inbound.ExpenseService;
import tz.co.pharmacy.modules.finance.ports.inbound.ExpenseView;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.Page;

@RestController
@RequestMapping("/api/v1/expenses")
public class ExpenseHandler {

  private final ExpenseService expenses;

  public ExpenseHandler(ExpenseService expenses) {
    this.expenses = expenses;
  }

  public record ExpenseRequest(
      String category,
      String description,
      BigDecimal amount,
      LocalDate expenseDate,
      String paymentMethod,
      String reference) {}

  /** The expenses of the request's branch, or of every branch the caller may use. */
  @GetMapping
  @Access(scope = AccessScope.TENANT_OPTIONAL_BRANCH, permission = "finance.view")
  public ApiResponse<Page<ExpenseView>> list(
      @RequestParam(required = false) String category,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    ExpenseCategory filter =
        category == null || category.isBlank()
            ? null
            : Checks.oneOf(ExpenseCategory.class, category, "category");
    return ApiResponse.ok(
        expenses.list(filter, from, to, Pages.of(cursor, limit)), "Expenses retrieved");
  }

  @Idempotent
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.BRANCH, permission = "finance.manage")
  public ApiResponse<ExpenseView> create(@RequestBody ExpenseRequest body) {
    ExpenseCommand command =
        new ExpenseCommand(
            Checks.oneOf(ExpenseCategory.class, body.category(), "category"),
            body.description(),
            body.amount(),
            body.expenseDate(),
            body.paymentMethod(),
            body.reference());
    return ApiResponse.created(expenses.record(command), "Expense recorded");
  }
}
