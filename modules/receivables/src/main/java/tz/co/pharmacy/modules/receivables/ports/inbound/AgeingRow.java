package tz.co.pharmacy.modules.receivables.ports.inbound;

import java.math.BigDecimal;
import java.util.UUID;

/** Open charges of one customer by age since the sale: 0-30, 31-60, 61-90 and over 90 days. */
public record AgeingRow(
    UUID customerUid,
    String customerName,
    BigDecimal current,
    BigDecimal d31to60,
    BigDecimal d61to90,
    BigDecimal over90,
    BigDecimal total) {}
