package tz.co.pharmacy.modules.inventory.ports.inbound;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A transfer between branches. {@code dispatchedBy} sent it and {@code receivedBy} received it
 * (never the same person); {@code daysInTransit} counts whole days since dispatch while it is in
 * transit (null once received).
 */
public record TransferView(
    UUID uid,
    String number,
    UUID fromBranchUid,
    String fromBranchName,
    UUID toBranchUid,
    String toBranchName,
    TransferStatus status,
    String note,
    List<TransferLineView> lines,
    Instant createdAt,
    Instant receivedAt,
    String dispatchedBy,
    String receivedBy,
    Long daysInTransit) {}
