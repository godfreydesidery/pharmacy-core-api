package tz.co.pharmacy.modules.finance.ports.inbound;

import java.math.BigDecimal;

/** An account of the chart with its balance on its normal side. */
public record AccountView(String code, String name, AccountType type, BigDecimal balance) {}
