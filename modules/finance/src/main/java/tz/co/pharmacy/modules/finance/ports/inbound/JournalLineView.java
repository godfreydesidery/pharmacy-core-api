package tz.co.pharmacy.modules.finance.ports.inbound;

import java.math.BigDecimal;

public record JournalLineView(
    String accountCode,
    String accountName,
    BigDecimal debit,
    BigDecimal credit,
    /* The foreign currency and amount behind the line, for reference; null for base-only lines. */
    String currency,
    BigDecimal foreignAmount) {}
