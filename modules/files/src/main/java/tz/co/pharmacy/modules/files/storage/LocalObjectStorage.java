package tz.co.pharmacy.modules.files.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import tz.co.pharmacy.modules.files.ports.outbound.ObjectStorage;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * Files on a local directory, for development and single-node installs (one instance only: the
 * directory is not shared). Its "presigned" GET URLs are API URLs, {@code
 * /api/v1/files/blob/{key}?exp=&ct=&cd=&sig=}, signed by {@link BlobUrls} and served by {@code
 * SignedBlobs}, so the browser flow is the same as with S3.
 */
public class LocalObjectStorage implements ObjectStorage {

  public static final String BLOB_PATH = BlobUrls.PATH;

  private final Path root;
  private final BlobUrls urls;

  public LocalObjectStorage(Path root, BlobUrls urls) {
    this.root = root.toAbsolutePath().normalize();
    this.urls = urls;
  }

  @Override
  public String name() {
    return "local";
  }

  @Override
  public String presignGet(String key, String contentType, String disposition, Duration ttl) {
    return urls.get(key, contentType, disposition, ttl);
  }

  /** The directory the files are in. */
  public Path root() {
    return root;
  }

  @Override
  public void put(String key, String contentType, InputStream content, long size) {
    Path target = path(key);
    Path partial = target.resolveSibling(target.getFileName() + ".part");
    try {
      Files.createDirectories(target.getParent());
      Files.copy(content, partial, StandardCopyOption.REPLACE_EXISTING);
      Files.move(
          partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException exception) {
      deleteQuietly(partial);
      throw new UncheckedIOException("could not store " + key, exception);
    } catch (RuntimeException refused) {
      deleteQuietly(partial);
      throw refused;
    }
  }

  @Override
  public boolean exists(String key) {
    return Files.isRegularFile(path(key));
  }

  @Override
  public long size(String key) {
    Path path = path(key);
    if (!Files.isRegularFile(path)) {
      throw Refusal.notFound("file_not_found", "No such file.");
    }
    try {
      return Files.size(path);
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  @Override
  public InputStream get(String key) {
    try {
      return Files.newInputStream(path(key));
    } catch (IOException exception) {
      throw Refusal.notFound("file_not_found", "No such file.");
    }
  }

  @Override
  public void delete(String key) {
    try {
      Files.deleteIfExists(path(key));
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  private static void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // a leftover .part file is harmless; the next upload of the key replaces it
    }
  }

  /** The file of a key, refusing any key that would leave the root. */
  Path path(String key) {
    if (key == null || key.isBlank() || key.contains("..") || key.startsWith("/")) {
      throw Refusal.forbidden("invalid_signature", "This file link is not valid.");
    }
    Path path = root.resolve(key).normalize();
    if (!path.startsWith(root)) {
      throw Refusal.forbidden("invalid_signature", "This file link is not valid.");
    }
    return path;
  }
}
