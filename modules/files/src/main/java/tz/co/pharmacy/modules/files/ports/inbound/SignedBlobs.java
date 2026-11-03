package tz.co.pharmacy.modules.files.ports.inbound;

import java.io.InputStream;

/**
 * The blob routes of the local store: the presigned URLs it hands out point at the API itself, and
 * these methods serve them. They need no session and no tenant: the URL's HMAC signature and expiry
 * are the authorisation, as with an S3 presigned URL ({@code 403 invalid_signature}, {@code 403
 * url_expired}). With the S3 store they refuse every request: the browser talks to S3 then.
 */
public interface SignedBlobs {

  /** Stores the bytes of a presigned PUT of {@code key}. */
  void put(String key, long expires, String signature, String contentType, InputStream content);

  /** Opens a presigned GET of {@code key}. */
  Blob get(String key, long expires, String signature, String contentType);

  record Blob(String contentType, long size, InputStream content) {}
}
