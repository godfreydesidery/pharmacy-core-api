package tz.co.pharmacy.modules.files.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Copies the local store's directory into the S3 store under the same keys, and checks each copy.
 * Run it as often as needed: it is idempotent and resumable, because an object already in S3 with
 * the same size and the same SHA-256 (kept in the object's {@code sha256} metadata) is skipped, and
 * anything else (missing, different, half-copied) is copied again. Unfinished local uploads ({@code
 * .part}) are left out; a file deleted while the copy runs is counted as vanished, not as a
 * failure.
 *
 * <p>Checks: {@link Verify#HEAD} compares each object's size and recorded SHA-256 with the local
 * file after the copy (S3 itself checks each upload's integrity on the way in); {@link Verify#FULL}
 * also reads every object back and hashes it.
 */
public final class LocalToS3Copy {

  private static final Logger LOG = LoggerFactory.getLogger(LocalToS3Copy.class);

  /** How each object is checked. */
  public enum Verify {
    HEAD,
    FULL
  }

  /** What one run did; {@link #ok()} when nothing failed. */
  public record Report(
      long files, long copied, long skipped, long vanished, long bytesCopied, List<String> failed) {
    public boolean ok() {
      return failed.isEmpty();
    }
  }

  private final Path root;
  private final S3ObjectStorage target;
  private final Verify verify;

  public LocalToS3Copy(Path root, S3ObjectStorage target, Verify verify) {
    this.root = root.toAbsolutePath().normalize();
    this.target = target;
    this.verify = verify;
  }

  public Report run() {
    if (!Files.isDirectory(root)) {
      throw new IllegalStateException("The local file root " + root + " is not a directory");
    }
    long files = 0;
    long copied = 0;
    long skipped = 0;
    long vanished = 0;
    long bytes = 0;
    List<String> failed = new ArrayList<>();
    List<Path> paths;
    try (Stream<Path> walk = Files.walk(root)) {
      paths =
          walk.filter(Files::isRegularFile)
              .filter(path -> !path.getFileName().toString().endsWith(".part"))
              .sorted()
              .toList();
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
    for (Path path : paths) {
      String key = key(path);
      files++;
      try {
        long size = copy(key, path);
        if (size < 0) {
          skipped++;
        } else {
          copied++;
          bytes += size;
        }
      } catch (NoSuchFileException gone) {
        vanished++;
      } catch (IOException | RuntimeException failure) {
        failed.add(key + ": " + failure);
        LOG.warn("Could not copy {} to S3: {}", key, failure.toString());
      }
      if (files % 500 == 0) {
        LOG.info("Copy to S3: {} files seen, {} copied, {} already there", files, copied, skipped);
      }
    }
    return new Report(files, copied, skipped, vanished, bytes, List.copyOf(failed));
  }

  /** Copies one file unless S3 has it already; returns the bytes copied, or -1 when skipped. */
  private long copy(String key, Path path) throws IOException {
    long size = Files.size(path);
    String sha256 = sha256(path);
    Optional<S3ObjectStorage.Head> head = target.head(key);
    if (head.isPresent() && same(head.get(), size, sha256) && fullyChecked(key, sha256)) {
      return -1;
    }
    try (InputStream in = Files.newInputStream(path)) {
      target.put(
          key,
          "application/octet-stream",
          in,
          size,
          Map.of(S3ObjectStorage.SHA256_METADATA, sha256));
    }
    S3ObjectStorage.Head stored =
        target.head(key).orElseThrow(() -> new IllegalStateException("not in S3 after the copy"));
    if (!same(stored, size, sha256)) {
      throw new IllegalStateException(
          "S3 has " + stored.size() + " bytes and " + stored.metadata() + " after the copy");
    }
    if (!fullyChecked(key, sha256)) {
      throw new IllegalStateException("the bytes read back from S3 have another SHA-256");
    }
    return size;
  }

  private static boolean same(S3ObjectStorage.Head head, long size, String sha256) {
    return head.size() == size
        && sha256.equals(head.metadata().get(S3ObjectStorage.SHA256_METADATA));
  }

  /** True unless a full check is asked for and the object's bytes hash differently. */
  private boolean fullyChecked(String key, String sha256) throws IOException {
    if (verify != Verify.FULL) {
      return true;
    }
    try (InputStream in = target.get(key)) {
      return sha256.equals(digest(in));
    }
  }

  /** The store key of a file: its path under the root, with forward slashes. */
  private String key(Path path) {
    return root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
  }

  private static String sha256(Path path) throws IOException {
    try (InputStream in = Files.newInputStream(path)) {
      return digest(in);
    }
  }

  static String digest(InputStream content) throws IOException {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
    try (DigestInputStream in = new DigestInputStream(content, digest)) {
      in.transferTo(java.io.OutputStream.nullOutputStream());
    }
    return HexFormat.of().formatHex(digest.digest());
  }
}
