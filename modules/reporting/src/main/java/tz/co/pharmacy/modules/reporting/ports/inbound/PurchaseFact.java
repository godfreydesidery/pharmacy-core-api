package tz.co.pharmacy.modules.reporting.ports.inbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PurchaseFact(UUID eventId, Instant occurredAt, UUID branchUid, BigDecimal total) {}
