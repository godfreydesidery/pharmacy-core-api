package tz.co.pharmacy.modules.reporting.ports.inbound;

import java.math.BigDecimal;
import java.util.UUID;

public record ProductFact(
    UUID productUid, String productName, int quantity, BigDecimal net, BigDecimal cost) {}
