package tz.co.pharmacy.modules.purchasing.ports.inbound;

import java.math.BigDecimal;
import java.util.UUID;

public record OrderLineCommand(UUID productUid, int quantity, BigDecimal unitCost) {}
