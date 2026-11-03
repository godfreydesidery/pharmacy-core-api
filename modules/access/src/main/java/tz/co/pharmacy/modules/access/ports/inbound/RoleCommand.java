package tz.co.pharmacy.modules.access.ports.inbound;

import java.util.List;

public record RoleCommand(String code, String name, String description, List<String> permissions) {}
