package tz.co.pharmacy.modules.notifications.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tz.co.pharmacy.modules.notifications.domain.InboxItem;

public interface InboxRepository
    extends JpaRepository<InboxItem, Long>, JpaSpecificationExecutor<InboxItem> {

  Optional<InboxItem> findByUidAndUserUid(UUID uid, UUID userUid);

  long countByUserUidAndReadAtIsNull(UUID userUid);

  Optional<InboxItem> findFirstByUserUidOrderByUidDesc(UUID userUid);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("update InboxItem i set i.readAt = :now where i.userUid = :userUid and i.readAt is null")
  int markAllRead(@Param("userUid") UUID userUid, @Param("now") Instant now);
}
