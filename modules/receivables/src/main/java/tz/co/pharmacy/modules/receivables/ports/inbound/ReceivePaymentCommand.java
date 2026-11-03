package tz.co.pharmacy.modules.receivables.ports.inbound;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A payment on account. {@code amount} is in {@code currency} (null = the base currency); a foreign
 * payment needs {@code exchangeRate} (base units per 1 unit) or uses the day's rate. {@code
 * allocateTo} names the charges to settle, in order; empty settles the oldest first. What is left
 * over stays on the account as credit.
 */
public record ReceivePaymentCommand(
    UUID customerUid,
    BigDecimal amount,
    String method,
    String reference,
    LocalDate date,
    List<UUID> allocateTo,
    String currency,
    BigDecimal exchangeRate) {}
