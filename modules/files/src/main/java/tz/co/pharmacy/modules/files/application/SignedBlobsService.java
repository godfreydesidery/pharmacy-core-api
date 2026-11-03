package tz.co.pharmacy.modules.files.application;

import java.io.IOException;
import java.io.InputStream;
import org.springframework.stereotype.Service;
import tz.co.pharmacy.modules.files.ports.inbound.FileRules;
import tz.co.pharmacy.modules.files.ports.inbound.SignedBlobs;
import tz.co.pharmacy.modules.files.ports.outbound.ObjectStorage;
import tz.co.pharmacy.modules.files.storage.LocalObjectStorage;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.kernel.RefusalKind;

/**
 * Serves the local store's presigned URLs. The signature binds method, key, expiry and content
 * type; a PUT is capped at {@link FileRules#MAX_SIZE}. No tenant is involved: the key itself names
 * the tenant, and only the API can sign one.
 */
@Service
class SignedBlobsService implements SignedBlobs {

  private final ObjectStorage storage;

  SignedBlobsService(ObjectStorage storage) {
    this.storage = storage;
  }

  @Override
  public void put(
      String key, long expires, String signature, String contentType, InputStream content) {
    LocalObjectStorage local = local();
    local.signer().verify("PUT", key, expires, FileRules.contentType(contentType), signature);
    local.put(key, FileRules.contentType(contentType), new Capped(content), -1);
  }

  @Override
  public Blob get(String key, long expires, String signature, String contentType) {
    LocalObjectStorage local = local();
    local.signer().verify("GET", key, expires, contentType, signature);
    if (!local.exists(key)) {
      throw Refusal.notFound("file_not_found", "No such file.");
    }
    return new Blob(contentType, local.size(key), local.get(key));
  }

  private LocalObjectStorage local() {
    if (storage instanceof LocalObjectStorage local) {
      return local;
    }
    throw Refusal.forbidden("invalid_signature", "This file link is not valid.");
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
        throw new Refusal(
            RefusalKind.PAYLOAD_TOO_LARGE, "file_too_large", "A file may be at most 10 MB.", null);
      }
    }

    @Override
    public void close() throws IOException {
      in.close();
    }
  }
}
