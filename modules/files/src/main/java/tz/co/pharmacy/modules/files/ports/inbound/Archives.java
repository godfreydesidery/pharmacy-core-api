package tz.co.pharmacy.modules.files.ports.inbound;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

/**
 * Archives the platform makes for the current tenant, such as its full data export: stored in the
 * same object store as its files (local disk or S3-compatible), under {@code
 * tenants/{tenantUid}/exports/{fileUid}/{name}}, but never listed, linked or deleted through the
 * ordinary file routes ({@link Files} does not see them). Only the caller that made one hands out
 * its link, after its own permission check.
 */
public interface Archives {

  /** The owner type of an archive's file record. */
  String OWNER_TYPE = "DATA_EXPORT";

  /**
   * Stores {@code content} (a finished file of {@code size} bytes) as an archive of {@code
   * contentType} named {@code name}, belonging to {@code ownerUid} (such as the export job).
   */
  FileView store(String name, String contentType, UUID ownerUid, Path content, long size);

  /** A presigned GET of the archive, valid {@code ttl}; 404 file_not_found when it is gone. */
  DownloadLink link(UUID fileUid, Duration ttl);

  /**
   * Deletes the archive: its record is marked deleted, and its bytes are deleted from the store, or
   * queued for a retry ({@link FileDeletions}) when the store refuses. Deleting one already deleted
   * does nothing.
   */
  void delete(UUID fileUid);
}
