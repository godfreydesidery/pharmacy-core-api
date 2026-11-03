package tz.co.pharmacy.modules.catalogue.ports.inbound;

import java.time.Instant;
import java.util.UUID;

public record ManufacturerView(UUID uid, String name, String country, Instant createdAt) {}
