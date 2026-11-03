package tz.co.pharmacy.modules.receivables.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import tz.co.pharmacy.platform.kernel.Amounts;

/**
 * Matches a credit against open debits in the given order, as far as both go: a payment settling
 * several invoices oldest first, or account credit paying a POS sale. Pure: it only moves open
 * amounts and returns the allocations to save.
 */
public final class Allocator {

  private Allocator() {}

  public static List<Allocation> allocate(Entry credit, List<Entry> debits) {
    List<Allocation> allocations = new ArrayList<>();
    for (Entry debit : debits) {
      BigDecimal available = credit.getOpenAmount();
      if (available.signum() <= 0) {
        break;
      }
      BigDecimal open = debit.getOpenAmount();
      if (open.signum() <= 0) {
        continue;
      }
      BigDecimal value = Amounts.money(available.min(open));
      credit.consume(value);
      debit.consume(value);
      allocations.add(new Allocation(credit, debit, value));
    }
    return allocations;
  }

  /** Spends open credits, oldest first, on one debit. */
  public static List<Allocation> settle(Entry debit, List<Entry> credits) {
    List<Allocation> allocations = new ArrayList<>();
    for (Entry credit : credits) {
      if (debit.getOpenAmount().signum() <= 0) {
        break;
      }
      BigDecimal value = Amounts.money(credit.getOpenAmount().min(debit.getOpenAmount()));
      if (value.signum() <= 0) {
        continue;
      }
      credit.consume(value);
      debit.consume(value);
      allocations.add(new Allocation(credit, debit, value));
    }
    return allocations;
  }
}
