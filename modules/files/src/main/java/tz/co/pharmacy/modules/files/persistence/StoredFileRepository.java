package tz.co.pharmacy.modules.files.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.pharmacy.modules.files.domain.StoredFile;

public interface StoredFileRepository extends JpaRepository<StoredFile, Long> {

  Optional<StoredFile> findByUid(UUID uid);

  List<StoredFile> findByOwnerTypeAndOwnerUidAndStatusOrderByUidDesc(
      String ownerType, UUID ownerUid, StoredFile.Status status);
}
