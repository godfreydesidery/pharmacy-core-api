package tz.co.pharmacy.modules.access.ports.inbound;

import java.util.List;
import java.util.UUID;

public record RoleView(
    UUID uid,
    String code,
    String name,
    String description,
    boolean system,
    List<String> permissions,
    /* The row's version: the ETag of the resource and what If-Match names. */
    long version)
    implements tz.co.pharmacy.platform.kernel.Versioned {}
