package tz.co.pharmacy.modules.files.storage;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * The API's own presigned URLs, {@code /api/v1/files/blob/{key}?exp=&ct=[&cd=]&sig=}, served by
 * {@code SignedBlobs}. Uploads always use them, whatever the store: the bytes come through the API,
 * which checks the size and the type while it streams them on to the store (local disk or S3).
 * Downloads use them only with the local store; S3 hands out its own presigned GET instead.
 */
public final class BlobUrls {

  public static final String PATH = "/api/v1/files/blob/";

  private final UrlSigner signer;

  public BlobUrls(UrlSigner signer) {
    this.signer = signer;
  }

  /** A PUT of {@code key}, sent with {@code Content-Type: contentType}, valid {@code ttl}. */
  public String put(String key, String contentType, Duration ttl) {
    long expires = signer.expiresIn(ttl);
    return PATH
        + key
        + "?exp="
        + expires
        + "&ct="
        + encode(contentType)
        + "&sig="
        + signer.sign("PUT", key, expires, contentType);
  }

  /** A GET of {@code key}, served as {@code contentType} with {@code disposition}. */
  public String get(String key, String contentType, String disposition, Duration ttl) {
    long expires = signer.expiresIn(ttl);
    String cd = disposition == null || disposition.isEmpty() ? "" : "&cd=" + encode(disposition);
    return PATH
        + key
        + "?exp="
        + expires
        + "&ct="
        + encode(contentType)
        + cd
        + "&sig="
        + signer.sign("GET", key, expires, contentType, disposition);
  }

  public void verifyPut(String key, long expires, String contentType, String signature) {
    signer.verify("PUT", key, expires, contentType, signature);
  }

  public void verifyGet(
      String key, long expires, String contentType, String disposition, String signature) {
    signer.verify("GET", key, expires, contentType, disposition, signature);
  }

  private static String encode(String value) {
    return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
  }
}
