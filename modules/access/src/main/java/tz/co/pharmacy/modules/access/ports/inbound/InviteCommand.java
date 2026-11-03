package tz.co.pharmacy.modules.access.ports.inbound;

import java.util.List;
import java.util.UUID;

public record InviteCommand(
    String name, String email, UUID roleUid, boolean allBranches, List<UUID> branchUids) {}
