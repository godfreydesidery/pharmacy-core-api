package tz.co.pharmacy.modules.access.ports.inbound;

import java.util.List;
import java.util.UUID;

public record ChangeMemberCommand(
    UUID roleUid, boolean allBranches, List<UUID> branchUids, boolean active) {}
