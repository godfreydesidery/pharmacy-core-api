package tz.co.pharmacy.modules.inventory.ports.inbound;

import java.util.UUID;

/** {@code productType} is a catalogue product type, such as MEDICAL_EQUIPMENT; null for all. */
public record StockFilter(String q, StockStatus status, UUID categoryUid, String productType) {}
