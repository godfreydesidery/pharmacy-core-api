package tz.co.pharmacy.modules.inventory.ports.inbound;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
    Instant receivedAt) {}
