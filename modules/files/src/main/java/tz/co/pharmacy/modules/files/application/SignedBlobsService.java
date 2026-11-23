package tz.co.pharmacy.modules.files.application;

import java.io.IOException;
import java.io.InputStream;
import org.springframework.stereotype.Service;
import tz.co.pharmacy.modules.files.ports.inbound.FileRules;
import tz.co.pharmacy.modules.files.ports.inbound.SignedBlobs;
import tz.co.pharmacy.modules.files.ports.outbound.ObjectStorage;
import tz.co.pharmacy.modules.files.storage.BlobUrls;
import tz.co.pharmacy.modules.files.storage.LocalObjectStorage;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.kernel.RefusalKind;

/**
 * Serves the API's presigned URLs. The signature binds method, key, expiry, content type (and a
 * GET's disposition); a PUT is capped at {@link FileRules#MAX_SIZE} and streamed on to whichever
 * store is configured, local or S3, never held whole in memory. No tenant is involved: the key
 * itself names the tenant, and only the API can sign one.
 */
@Service
class SignedBlobsService implements SignedBlobs {

  private final ObjectStorage storage;
  private final BlobUrls urls;

  SignedBlobsService(ObjectStorage storage, BlobUrls urls) {
    this.storage = storage;
    this.urls = urls;
  }

  @Override
  public void put(
      String key,
      long expires,
      String signature,
      String contentType,
      long size,
      InputStream content) {
    String type = FileRules.contentType(contentType);
    urls.verifyPut(key, expires, type, signature);
    if (size > FileRules.MAX_SIZE) {
      throw tooLarge();
    }
    storage.put(key, type, new Capped(content), size < 0 ? -1 : size);
  }

  @Override
  public Blob get(
      String key, long expires, String signature, String contentType, String disposition) {
    if (!(storage instanceof LocalObjectStorage local)) {
      throw Refusal.forbidden("invalid_signature", "This file link is not valid.");
    }
    urls.verifyGet(key, expires, contentType, disposition, signature);
    if (!local.exists(key)) {
      throw Refusal.notFound("file_not_found", "No such file.");
    }
    return new Blob(
        contentType,
        disposition == null || disposition.isBlank() ? "inline" : disposition,
        local.size(key),
        local.get(key));
  }

  private static Refusal tooLarge() {
    return new Refusal(
        RefusalKind.PAYLOAD_TOO_LARGE, "file_too_large", "A file may be at most 10 MB.", null);
  }

  /** Refuses a body larger than the maximum file size while it is being read. */
  static final class Capped extends InputStream {
    private final InputStream in;
    private long read;

    Capped(InputStream in) {
      this.in = in;
    }

    @Override
    public int read() throws IOException {
      int b = in.read();
      if (b >= 0) {
        count(1);
      }
      return b;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      int n = in.read(buffer, offset, length);
      if (n > 0) {
        count(n);
      }
      return n;
    }

    private void count(long n) {
      read += n;
      if (read > FileRules.MAX_SIZE) {
        throw tooLarge();
      }
    }

    @Override
    public void close() throws IOException {
      in.close();
    }
  }
}
