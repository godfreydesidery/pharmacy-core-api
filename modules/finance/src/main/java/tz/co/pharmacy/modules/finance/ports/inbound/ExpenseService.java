package tz.co.pharmacy.modules.finance.ports.inbound;

import java.time.LocalDate;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;

public interface ExpenseService {

  /**
   * Records an expense at the current branch (EXP-000001), posts its journal entry in the same
   * transaction and publishes expense.recorded.
   */
  ExpenseView record(ExpenseCommand command);

  /** Expenses of the report branches, newest first; each filter is optional. */
  Page<ExpenseView> list(ExpenseCategory category, LocalDate from, LocalDate to, PageQuery query);
}
