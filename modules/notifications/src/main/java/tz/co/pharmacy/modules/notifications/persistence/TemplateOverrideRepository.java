package tz.co.pharmacy.modules.notifications.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.pharmacy.modules.notifications.domain.TemplateOverride;
import tz.co.pharmacy.modules.notifications.ports.inbound.Channel;

public interface TemplateOverrideRepository extends JpaRepository<TemplateOverride, Long> {

  Optional<TemplateOverride> findByTemplateKeyAndChannelAndLocale(
      String templateKey, Channel channel, String locale);
}
