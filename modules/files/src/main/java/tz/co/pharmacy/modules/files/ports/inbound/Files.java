package tz.co.pharmacy.modules.files.ports.inbound;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The files of the current tenant. */
public interface Files {

  /**
   * Records a pending file and returns a presigned PUT for its bytes. Refuses a type other than
   * PNG, JPEG, WebP or PDF ({@code 415 unsupported_type}) and a file over 10 MB ({@code 413
   * file_too_large}).
   */
  UploadTicket beginUpload(UploadCommand command);

  /** Verifies the bytes arrived, records their size and SHA-256, and makes the file visible. */
  FileView completeUpload(UUID fileUid);

  /** Stores a small file sent through the API itself, such as a multipart form. */
  FileView uploadDirect(UploadCommand command, InputStream content);

  /** A presigned GET of the file, valid five minutes. */
  String downloadUrl(UUID fileUid);

  /** As {@link #downloadUrl}, with its expiry, for an {@code <img src>}. */
  DownloadLink downloadLink(UUID fileUid);

  /** A completed file of the tenant. */
  Optional<FileView> find(UUID fileUid);

  /** The completed files attached to an owner, newest first. */
  List<FileView> attachments(String ownerType, UUID ownerUid);

  /** Removes the file from the store and from its owner. */
  void delete(UUID fileUid);
}
