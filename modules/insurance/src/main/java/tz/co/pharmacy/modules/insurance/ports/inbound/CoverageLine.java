package tz.co.pharmacy.modules.insurance.ports.inbound;

import java.math.BigDecimal;
import java.util.UUID;

/** One basket line to quote: its total as charged, tax included. */
public record CoverageLine(UUID productUid, String productType, BigDecimal lineTotal) {}
