package tz.co.pharmacy.modules.files.ports.inbound;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Where the browser PUTs the file's bytes, with the headers it must send, until {@code expiresAt};
 * then it calls complete with {@code fileUid}.
 */
public record UploadTicket(
    UUID fileUid, String uploadUrl, Map<String, String> headers, Instant expiresAt) {}
