package tz.co.pharmacy.modules.reporting.ports.inbound;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record ExpenseFact(
    UUID eventId, LocalDate date, UUID branchUid, String category, BigDecimal amount) {}
