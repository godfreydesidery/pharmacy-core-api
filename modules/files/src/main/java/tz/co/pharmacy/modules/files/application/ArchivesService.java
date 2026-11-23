package tz.co.pharmacy.modules.files.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.pharmacy.modules.files.domain.StoredFile;
import tz.co.pharmacy.modules.files.persistence.StoredFileRepository;
import tz.co.pharmacy.modules.files.ports.inbound.Archives;
import tz.co.pharmacy.modules.files.ports.inbound.DownloadLink;
import tz.co.pharmacy.modules.files.ports.inbound.FileDeletions;
import tz.co.pharmacy.modules.files.ports.inbound.FileRules;
import tz.co.pharmacy.modules.files.ports.inbound.FileView;
import tz.co.pharmacy.modules.files.ports.outbound.ObjectStorage;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.persistence.TenantContext;

/**
 * The current tenant's archives. The bytes go to the store first, outside any transaction (an
 * upload to S3 may take a while); the record is written after, in a short one. When the record
 * cannot be written, the bytes are taken back out of the store.
 */
@Service
class ArchivesService implements Archives {

  private static final String PURPOSE = "EXPORT";

  private final StoredFileRepository files;
  private final ObjectStorage storage;
  private final FileDeletions deletions;
  private final TransactionTemplate tx;
  private final TransactionTemplate reading;
  private final Clock clock;

  ArchivesService(
      StoredFileRepository files,
      ObjectStorage storage,
      FileDeletions deletions,
      PlatformTransactionManager transactions,
      Clock clock) {
    this.files = files;
    this.storage = storage;
    this.deletions = deletions;
    this.tx = new TransactionTemplate(transactions);
    this.reading = new TransactionTemplate(transactions);
    this.reading.setReadOnly(true);
    this.clock = clock;
  }

  @Override
  public FileView store(String name, String contentType, UUID ownerUid, Path content, long size) {
    UUID tenant = TenantContext.requireTenant();
    StoredFile file =
        new StoredFile(
            FileRules.name(name), contentType, size, OWNER_TYPE, ownerUid, PURPOSE, storage.name());
    String key = FileRules.archiveKey(tenant, file.getUid(), file.getName());
    file.placeAt(key);
    MessageDigest digest = sha256();
    try (InputStream in =
        new DigestInputStream(java.nio.file.Files.newInputStream(content), digest)) {
      storage.put(key, contentType, in, size);
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
    file.complete(size, HexFormat.of().formatHex(digest.digest()));
    try {
      return tx.execute(status -> FilesService.view(files.save(file)));
    } catch (RuntimeException failure) {
      deletions.delete(key, "archive record not written");
      throw failure;
    }
  }

  @Override
  public DownloadLink link(UUID fileUid, Duration ttl) {
    StoredFile file =
        reading.execute(
            status ->
                archive(fileUid)
                    .filter(f -> f.getStatus() == StoredFile.Status.COMPLETE)
                    .orElseThrow(() -> Refusal.notFound("file_not_found", "No such file.")));
    return new DownloadLink(
        storage.presignGet(file.getStorageKey(), file.getContentType(), ttl),
        clock.instant().plus(ttl));
  }

  @Override
  public void delete(UUID fileUid) {
    String key =
        tx.execute(
            status ->
                archive(fileUid)
                    .filter(f -> f.getStatus() != StoredFile.Status.DELETED)
                    .map(
                        f -> {
                          f.markDeleted();
                          return f.getStorageKey();
                        })
                    .orElse(null));
    if (key != null) {
      deletions.delete(key, "archive deleted");
    }
  }

  private Optional<StoredFile> archive(UUID fileUid) {
    return files.findByUid(fileUid).filter(f -> OWNER_TYPE.equals(f.getOwnerType()));
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
