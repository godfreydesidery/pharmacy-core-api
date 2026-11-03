package tz.co.pharmacy.modules.files.ports.inbound;

import java.time.Instant;

/** A short-lived presigned URL a browser can use directly, such as an {@code <img src>}. */
public record DownloadLink(String url, Instant expiresAt) {}
