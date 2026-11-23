package tz.co.pharmacy.modules.files.ports.inbound;

import java.io.InputStream;

/**
 * The API's blob routes: the presigned URLs it hands out for uploads (with every store) and for the
 * local store's downloads point at the API itself, and these methods serve them. They need no
 * session and no tenant: the URL's HMAC signature and expiry are the authorisation, as with an S3
 * presigned URL ({@code 403 invalid_signature}, {@code 403 url_expired}). With the S3 store a GET
 * is refused: the browser downloads from S3 then.
 */
public interface SignedBlobs {

  /**
   * Stores the bytes of a presigned PUT of {@code key}, streaming them on to the store. {@code
   * size} is the request's Content-Length, or -1 when it has none; more than 10 MB is refused
   * ({@code 413 file_too_large}) before or while reading.
   */
  void put(
      String key,
      long expires,
      String signature,
      String contentType,
      long size,
      InputStream content);

  /** Opens a presigned GET of {@code key} of the local store, with its signed disposition. */
  Blob get(String key, long expires, String signature, String contentType, String disposition);

  /** The bytes to send, as {@code contentType} with {@code Content-Disposition: disposition}. */
  record Blob(String contentType, String disposition, long size, InputStream content) {}
}
