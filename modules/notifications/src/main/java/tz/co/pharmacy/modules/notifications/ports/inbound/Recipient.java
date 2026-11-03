package tz.co.pharmacy.modules.notifications.ports.inbound;

import java.util.UUID;

/**
 * Who a message is for. Any of {@code userUid}, {@code email} and {@code phone} may be null; a
 * channel without an address is skipped. {@code locale} is {@code en} or {@code sw}, null for
 * English.
 */
public record Recipient(UUID userUid, String name, String email, String phone, String locale) {}
