package tz.co.pharmacy.modules.finance.ports.inbound;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record JournalEntryView(
    UUID uid,
    String number,
    LocalDate entryDate,
    String sourceType,
    String sourceReference,
    String description,
    UUID branchUid,
    List<JournalLineView> lines,
    Instant createdAt) {}
