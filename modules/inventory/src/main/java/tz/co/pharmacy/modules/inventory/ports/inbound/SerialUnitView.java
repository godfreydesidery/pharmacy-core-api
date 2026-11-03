package tz.co.pharmacy.modules.inventory.ports.inbound;

import java.time.Instant;
import java.util.UUID;

/** One serial-tracked unit, such as wheelchair WCH-0001, at a branch. */
public record SerialUnitView(
    UUID uid,
    UUID productUid,
    String serialNumber,
    UUID batchUid,
    String batchNumber,
    UUID branchUid,
    SerialStatus status,
    String saleReference,
    Instant soldAt,
    Instant createdAt) {}
