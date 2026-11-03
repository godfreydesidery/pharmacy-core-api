package tz.co.pharmacy.modules.receivables.ports.inbound;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A customer's account in the base currency. {@code balance} is what the customer owes, negative
 * when they hold credit; {@code available} is the credit limit less the balance; {@code
 * unallocatedCredit} is deposits and overpayments not yet used.
 */
public record AccountView(
    UUID customerUid,
    String customerName,
    BigDecimal creditLimit,
    BigDecimal balance,
    BigDecimal available,
    BigDecimal overdue,
    LocalDate oldestOpenDate,
    boolean onHold,
    String holdReason,
    BigDecimal unallocatedCredit,
    int termsDays) {}
