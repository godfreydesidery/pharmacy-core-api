package tz.co.pharmacy.modules.notifications.application;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.notifications.domain.Message;
import tz.co.pharmacy.modules.notifications.domain.MessageStatus;
import tz.co.pharmacy.modules.notifications.persistence.MessageRepository;
import tz.co.pharmacy.modules.notifications.ports.inbound.Channel;
import tz.co.pharmacy.modules.notifications.ports.inbound.DeliveryLog;
import tz.co.pharmacy.modules.notifications.ports.inbound.DeliveryView;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;
import tz.co.pharmacy.platform.persistence.Keyset;
import tz.co.pharmacy.platform.persistence.Specs;

@Service
class DeliveryLogImpl implements DeliveryLog {

  private final MessageRepository messages;

  DeliveryLogImpl(MessageRepository messages) {
    this.messages = messages;
  }

  @Override
  @Transactional(readOnly = true)
  public Page<DeliveryView> list(Channel channel, String status, PageQuery query) {
    MessageStatus wanted =
        status == null || status.isBlank()
            ? null
            : Checks.oneOf(MessageStatus.class, status, "status");
    Specification<Message> filter =
        Specs.<Message>equal("channel", channel).and(Specs.equal("status", wanted));
    return Keyset.page(
        messages,
        filter,
        query,
        m ->
            new DeliveryView(
                m.getUid(),
                m.getTemplateKey(),
                m.getChannel(),
                m.getToAddress(),
                m.getStatus().name(),
                m.getProvider(),
                m.getAttempts(),
                m.getLastError(),
                m.getCreatedAt(),
                m.getSentAt()));
  }
}
