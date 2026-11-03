package tz.co.pharmacy.modules.catalogue.ports.inbound;

import java.util.UUID;

/** {@code parentUid} is null for a top-level category; categories nest at most three deep. */
public record CategoryCommand(String name, String description, UUID parentUid) {}
