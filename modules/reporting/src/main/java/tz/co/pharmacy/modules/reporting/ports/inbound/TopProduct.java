package tz.co.pharmacy.modules.reporting.ports.inbound;

import java.math.BigDecimal;
import java.util.UUID;

public record TopProduct(UUID productUid, String productName, long quantity, BigDecimal net) {}
