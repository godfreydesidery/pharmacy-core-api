package tz.co.pharmacy.modules.finance.ports.inbound;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One account, day and branch (null: the pharmacy's own entries) where the daily balances read
 * model and the journal disagree: what the journal's lines add up to, and what the read model
 * holds. There should never be one; the books are read from the read model.
 */
public record BalanceMismatch(
    String accountCode,
    LocalDate day,
    UUID branchUid,
    BigDecimal journalDebit,
    BigDecimal journalCredit,
    BigDecimal readModelDebit,
    BigDecimal readModelCredit) {}
