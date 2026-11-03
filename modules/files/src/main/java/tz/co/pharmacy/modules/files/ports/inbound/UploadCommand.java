package tz.co.pharmacy.modules.files.ports.inbound;

import java.util.UUID;

/**
 * A file to upload: its name, type and size, and what it belongs to. {@code ownerType} is one of
 * {@link FileRules#OWNER_TYPES}, such as {@code SALE}; {@code purpose} one of {@link
 * FileRules#PURPOSES}, such as {@code PRESCRIPTION}.
 */
public record UploadCommand(
    String name, String contentType, long size, String ownerType, UUID ownerUid, String purpose) {}
