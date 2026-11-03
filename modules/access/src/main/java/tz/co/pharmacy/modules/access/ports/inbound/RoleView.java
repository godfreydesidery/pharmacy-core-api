package tz.co.pharmacy.modules.access.ports.inbound;

import java.util.List;
import java.util.UUID;

public record RoleView(
    UUID uid,
    String code,
    String name,
    String description,
    boolean system,
    List<String> permissions) {}
