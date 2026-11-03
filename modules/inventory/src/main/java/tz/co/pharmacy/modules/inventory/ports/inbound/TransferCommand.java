package tz.co.pharmacy.modules.inventory.ports.inbound;

import java.util.List;
import java.util.UUID;

public record TransferCommand(UUID toBranchUid, String note, List<TransferLineCommand> lines) {}
