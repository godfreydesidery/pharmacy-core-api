package tz.co.pharmacy.modules.partners.ports.inbound;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One entry of the request register. {@code dueOn}: {@link DataSubjectRequests#DUE_DAYS} days after
 * it was received (verify with counsel, C7); {@code overdue} while it is OPEN after that day. No
 * health data is kept here.
 */
public record DataSubjectRequestView(
    UUID uid,
    String number,
    RequestKind kind,
    RequestStatus status,
    UUID customerUid,
    String customerCode,
    String customerName,
    Instant receivedAt,
    LocalDate dueOn,
    Instant completedAt,
    String completedBy,
    String outcome,
    String note,
    boolean overdue,
    Instant createdAt,
    String createdBy) {}
