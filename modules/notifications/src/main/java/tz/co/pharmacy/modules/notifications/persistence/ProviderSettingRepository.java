package tz.co.pharmacy.modules.notifications.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tz.co.pharmacy.modules.notifications.domain.ProviderSetting;
import tz.co.pharmacy.modules.notifications.ports.inbound.Channel;

/**
 * A tenant session sees its own rows and the platform's (tenant_uid null); a platform session only
 * the platform's.
 */
public interface ProviderSettingRepository extends JpaRepository<ProviderSetting, Long> {

  /** A pharmacy's own providers of a channel, in the order they are tried. */
  List<ProviderSetting> findByTenantUidAndChannelOrderByPriorityAscIdAsc(
      UUID tenantUid, Channel channel);

  /** The platform's providers of a channel, in the order they are tried. */
  List<ProviderSetting> findByTenantUidIsNullAndChannelOrderByPriorityAscIdAsc(Channel channel);

  Optional<ProviderSetting> findByUid(UUID uid);

  /** A send through the provider worked: noted at most once a minute, with no version bump. */
  @Modifying
  @Query(
      "update ProviderSetting p set p.lastSentAt = :at where p.uid = :uid"
          + " and (p.lastSentAt is null or p.lastSentAt < :notBefore)")
  int markSent(
      @Param("uid") UUID uid, @Param("at") Instant at, @Param("notBefore") Instant notBefore);

  /** A send through the provider failed. */
  @Modifying
  @Query(
      "update ProviderSetting p set p.lastFailedAt = :at, p.lastError = :error where p.uid = :uid")
  int markFailed(@Param("uid") UUID uid, @Param("at") Instant at, @Param("error") String error);
}
