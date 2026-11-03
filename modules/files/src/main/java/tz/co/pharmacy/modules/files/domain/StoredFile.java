package tz.co.pharmacy.modules.files.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/**
 * The record of one file of a tenant. Its bytes are in the object store under {@code storageKey};
 * it is {@code PENDING} until the upload is confirmed, then {@code COMPLETE}; a deleted file is
 * kept as {@code DELETED} for the audit trail.
 */
@Getter
@Entity
@Table(schema = "files", name = "files")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoredFile extends TenantEntity {

  public enum Status {
    PENDING,
    COMPLETE,
    DELETED
  }

  private String name;
  private String contentType;
  private long sizeBytes;
  private String ownerType;
  private UUID ownerUid;
  private String purpose;
  private String storageKey;
  private String storageName;
  private String sha256;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Status status;

  public StoredFile(
      String name,
      String contentType,
      long sizeBytes,
      String ownerType,
      UUID ownerUid,
      String purpose,
      String storageName) {
    this.name = name;
    this.contentType = contentType;
    this.sizeBytes = sizeBytes;
    this.ownerType = ownerType;
    this.ownerUid = ownerUid;
    this.purpose = purpose;
    this.storageName = storageName;
    this.status = Status.PENDING;
  }

  /** The key is known once the uid is: {@code tenants/{tenant}/{yyyy}/{mm}/{uid}}. */
  public void placeAt(String storageKey) {
    this.storageKey = storageKey;
  }

  public void complete(long sizeBytes, String sha256) {
    this.sizeBytes = sizeBytes;
    this.sha256 = sha256;
    this.status = Status.COMPLETE;
  }

  public void markDeleted() {
    this.status = Status.DELETED;
  }
}
