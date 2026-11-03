package tz.co.pharmacy.modules.files.ports.outbound;

import java.io.InputStream;
import java.time.Duration;

/**
 * The object store the files live in. The browser talks to it directly through presigned URLs; the
 * API only signs them and checks the result. Implementations: {@code local} (a directory, with
 * HMAC-signed URLs served by this module) and {@code s3} (AWS S3, MinIO, Cloudflare R2), chosen by
 * {@code pharmacy.files.storage}.
 */
public interface ObjectStorage {

  String name();

  /** A URL the browser PUTs the bytes to, with {@code Content-Type: contentType}. */
  String presignPut(String key, String contentType, Duration ttl);

  /** A URL the browser GETs the bytes from, served as {@code contentType}. */
  String presignGet(String key, String contentType, Duration ttl);

  void put(String key, String contentType, InputStream content, long size);

  boolean exists(String key);

  long size(String key);

  InputStream get(String key);

  void delete(String key);
}
