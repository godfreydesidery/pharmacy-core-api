package tz.co.pharmacy.modules.files.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tz.co.pharmacy.modules.files.domain.StoredFile;
import tz.co.pharmacy.modules.files.persistence.StoredFileRepository;
import tz.co.pharmacy.modules.files.ports.inbound.Archives;
import tz.co.pharmacy.modules.files.ports.inbound.DownloadLink;
import tz.co.pharmacy.modules.files.ports.inbound.FileDeletions;
import tz.co.pharmacy.modules.files.ports.inbound.FileRules;
import tz.co.pharmacy.modules.files.ports.inbound.FileView;
import tz.co.pharmacy.modules.files.ports.inbound.Files;
import tz.co.pharmacy.modules.files.ports.inbound.UploadCommand;
import tz.co.pharmacy.modules.files.ports.inbound.UploadTicket;
import tz.co.pharmacy.modules.files.ports.outbound.ObjectStorage;
import tz.co.pharmacy.modules.files.storage.BlobUrls;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.kernel.RefusalKind;
import tz.co.pharmacy.platform.persistence.TenantContext;

/**
 * The files of the current tenant. An upload is two steps: {@link #beginUpload} records a pending
 * file and signs a PUT of the API's blob route, which checks the size and type and streams the
 * bytes on to the store (local or S3); {@link #completeUpload} checks they arrived, hashes them and
 * makes the file visible. A download is a short presigned GET of the store itself. A deleted file's
 * bytes go after the commit, or are queued for a retry ({@link FileDeletions}).
 */
@Service
class FilesService implements Files {

  static final Duration UPLOAD_TTL = Duration.ofMinutes(10);
  static final Duration DOWNLOAD_TTL = Duration.ofMinutes(5);

  private final StoredFileRepository files;
  private final ObjectStorage storage;
  private final BlobUrls urls;
  private final FileDeletions deletions;
  private final Clock clock;

  FilesService(
      StoredFileRepository files,
      ObjectStorage storage,
      BlobUrls urls,
      FileDeletions deletions,
      Clock clock) {
    this.files = files;
    this.storage = storage;
    this.urls = urls;
    this.deletions = deletions;
    this.clock = clock;
  }

  @Override
  @Transactional
  public UploadTicket beginUpload(UploadCommand command) {
    StoredFile file = files.save(newFile(command));
    String uploadUrl = urls.put(file.getStorageKey(), file.getContentType(), UPLOAD_TTL);
    return new UploadTicket(
        file.getUid(),
        uploadUrl,
        Map.of("Content-Type", file.getContentType()),
        clock.instant().plus(UPLOAD_TTL));
  }

  @Override
  @Transactional
  public FileView completeUpload(UUID fileUid) {
    StoredFile file = load(fileUid);
    if (file.getStatus() == StoredFile.Status.COMPLETE) {
      return view(file);
    }
    if (file.getStatus() != StoredFile.Status.PENDING || !storage.exists(file.getStorageKey())) {
      throw Refusal.invalid(
          "upload_missing", "The file's bytes have not arrived; upload them, then complete.");
    }
    long size = storage.size(file.getStorageKey());
    if (size > FileRules.MAX_SIZE) {
      // The refusal rolls the transaction back, so the bytes go now (or are queued), not after it.
      deletions.delete(file.getStorageKey(), "upload too large");
      file.markDeleted();
      throw new Refusal(
          RefusalKind.PAYLOAD_TOO_LARGE, "file_too_large", "A file may be at most 10 MB.", "size");
    }
    String sha256;
    try (InputStream content = storage.get(file.getStorageKey())) {
      sha256 = digest(content);
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
    file.complete(size, sha256);
    return view(file);
  }

  @Override
  @Transactional
  public FileView uploadDirect(UploadCommand command, InputStream content) {
    StoredFile file = files.save(newFile(command));
    MessageDigest digest = sha256();
    try (DigestInputStream hashing = new DigestInputStream(content, digest)) {
      storage.put(file.getStorageKey(), file.getContentType(), hashing, file.getSizeBytes());
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
    file.complete(storage.size(file.getStorageKey()), HexFormat.of().formatHex(digest.digest()));
    return view(file);
  }

  @Override
  @Transactional(readOnly = true)
  public String downloadUrl(UUID fileUid) {
    return downloadLink(fileUid).url();
  }

  @Override
  @Transactional(readOnly = true)
  public DownloadLink downloadLink(UUID fileUid) {
    StoredFile file = completed(fileUid);
    Instant expires = clock.instant().plus(DOWNLOAD_TTL);
    return new DownloadLink(
        storage.presignGet(
            file.getStorageKey(),
            file.getContentType(),
            FileRules.disposition(false, file.getName()),
            DOWNLOAD_TTL),
        expires);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<FileView> find(UUID fileUid) {
    return files
        .findByUid(fileUid)
        .filter(file -> file.getStatus() == StoredFile.Status.COMPLETE)
        .filter(FilesService::ordinary)
        .map(FilesService::view);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<FileView> findUpload(UUID fileUid) {
    return files
        .findByUid(fileUid)
        .filter(file -> file.getStatus() != StoredFile.Status.DELETED)
        .filter(FilesService::ordinary)
        .map(FilesService::view);
  }

  @Override
  @Transactional(readOnly = true)
  public List<FileView> attachments(String ownerType, UUID ownerUid) {
    return files
        .findByOwnerTypeAndOwnerUidAndStatusOrderByUidDesc(
            FileRules.ownerType(ownerType), ownerUid, StoredFile.Status.COMPLETE)
        .stream()
        .map(FilesService::view)
        .toList();
  }

  @Override
  @Transactional
  public void delete(UUID fileUid) {
    StoredFile file = load(fileUid);
    if (file.getStatus() == StoredFile.Status.DELETED) {
      return;
    }
    file.markDeleted();
    if (file.getStorageKey() != null) {
      deleteAfterCommit(file.getStorageKey(), "file deleted");
    }
  }

  /**
   * Deletes the bytes once the record is committed as deleted, never inside the transaction (the
   * store may be slow or down): what the store refuses is queued and retried by the retention.
   */
  private void deleteAfterCommit(String key, String reason) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      deletions.delete(key, reason);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            deletions.delete(key, reason);
          }
        });
  }

  private StoredFile newFile(UploadCommand command) {
    StoredFile file =
        new StoredFile(
            FileRules.name(command.name()),
            FileRules.contentType(command.contentType()),
            FileRules.size(command.size()),
            FileRules.ownerType(command.ownerType()),
            command.ownerUid(),
            FileRules.purpose(command.purpose()),
            storage.name());
    file.placeAt(FileRules.key(TenantContext.requireTenant(), clock.instant(), file.getUid()));
    return file;
  }

  private StoredFile load(UUID fileUid) {
    return files
        .findByUid(fileUid)
        .filter(FilesService::ordinary)
        .orElseThrow(() -> Refusal.notFound("file_not_found", "No such file."));
  }

  /** An archive (such as the full data export) is never reached through the file routes. */
  private static boolean ordinary(StoredFile file) {
    return !Archives.OWNER_TYPE.equals(file.getOwnerType());
  }

  private StoredFile completed(UUID fileUid) {
    StoredFile file = load(fileUid);
    if (file.getStatus() != StoredFile.Status.COMPLETE) {
      throw Refusal.notFound("file_not_found", "No such file.");
    }
    return file;
  }

  static String digest(InputStream content) throws IOException {
    MessageDigest digest = sha256();
    byte[] buffer = new byte[8192];
    for (int read = content.read(buffer); read >= 0; read = content.read(buffer)) {
      digest.update(buffer, 0, read);
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  static FileView view(StoredFile file) {
    return new FileView(
        file.getUid(),
        file.getName(),
        file.getContentType(),
        file.getSizeBytes(),
        file.getOwnerType(),
        file.getOwnerUid(),
        file.getPurpose(),
        file.getSha256(),
        file.getCreatedAt(),
        file.getCreatedBy());
  }
}
