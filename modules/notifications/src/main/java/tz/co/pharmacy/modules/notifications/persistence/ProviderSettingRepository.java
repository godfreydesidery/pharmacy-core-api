package tz.co.pharmacy.modules.notifications.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.pharmacy.modules.notifications.domain.ProviderSetting;
import tz.co.pharmacy.modules.notifications.ports.inbound.Channel;

/**
 * A tenant session sees its own rows and the platform's (tenant_uid null); a platform session only
 * the platform's.
 */
public interface ProviderSettingRepository extends JpaRepository<ProviderSetting, Long> {

  Optional<ProviderSetting> findByTenantUidAndChannel(UUID tenantUid, Channel channel);

  Optional<ProviderSetting> findByTenantUidIsNullAndChannel(Channel channel);

  List<ProviderSetting> findByTenantUidIsNull();

  List<ProviderSetting> findByTenantUid(UUID tenantUid);
}
