package tz.co.pharmacy.modules.finance.ports.inbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The opening balances as posted; {@code capital} is the owner's equity they produced. */
public record OpeningBalanceView(
    UUID uid,
    LocalDate asOf,
    BigDecimal cash,
    BigDecimal bank,
    BigDecimal mobileMoney,
    BigDecimal inventory,
    BigDecimal receivables,
    BigDecimal payables,
    BigDecimal capital,
    String journalNumber,
    List<PartyLine> parties,
    Instant createdAt,
    String createdBy) {

  /** {@code type} is CUSTOMER or SUPPLIER. */
  public record PartyLine(String type, UUID uid, String name, BigDecimal amount) {}
}
