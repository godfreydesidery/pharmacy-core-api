package tz.co.pharmacy.modules.files.ports.outbound;

import java.io.InputStream;
import java.time.Duration;

/**
 * The object store the files live in. Uploads come through the API (its signed blob route checks
 * the size and type and streams the bytes on with {@link #put}); downloads go straight from the
 * store with a short presigned GET. Implementations: {@code local} (a directory, whose GET links
 * are the API's own HMAC-signed blob route) and {@code s3} (AWS S3, MinIO, Cloudflare R2), chosen
 * by {@code pharmacy.files.storage}. Keys are the same in both, so files copy across unchanged.
 */
public interface ObjectStorage {

  String name();

  /**
   * A URL the browser GETs the bytes from, valid {@code ttl}, served as {@code contentType} with
   * the header {@code Content-Disposition: disposition} (such as {@code inline;
   * filename="scan.pdf"}).
   */
  String presignGet(String key, String contentType, String disposition, Duration ttl);

  /**
   * Stores {@code content} at {@code key}, streaming: {@code size} bytes, or -1 when the size is
   * not known (the store then buffers at most one part at a time). Replaces what was there.
   */
  void put(String key, String contentType, InputStream content, long size);

  boolean exists(String key);

  /** The size of the object; 404 file_not_found when there is none. */
  long size(String key);

  /** The bytes of the object, streaming; 404 file_not_found when there is none. */
  InputStream get(String key);

  /** Deletes the object; deleting one that is not there does nothing. */
  void delete(String key);
}
