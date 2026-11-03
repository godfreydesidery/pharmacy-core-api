package tz.co.pharmacy.modules.inventory.ports.inbound;

import java.util.List;
import java.util.UUID;

/** For a SERIAL product, {@code serialNumbers} name the units sent; else the oldest are. */
public record TransferLineCommand(UUID productUid, int quantity, List<String> serialNumbers) {}
