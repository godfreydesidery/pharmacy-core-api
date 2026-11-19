package tz.co.pharmacy.modules.notifications.application;

import java.time.Clock;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.notifications.domain.InboxItem;
import tz.co.pharmacy.modules.notifications.persistence.InboxRepository;
import tz.co.pharmacy.modules.notifications.ports.inbound.InAppInbox;
import tz.co.pharmacy.modules.notifications.ports.inbound.InAppView;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.persistence.Keyset;
import tz.co.pharmacy.platform.persistence.Specs;

@Service
class InAppInboxImpl implements InAppInbox {

  private final InboxRepository inbox;

  private final Clock clock;

  InAppInboxImpl(InboxRepository inbox, Clock clock) {
    this.clock = clock;
    this.inbox = inbox;
  }

  @Override
  @Transactional(readOnly = true)
  public Page<InAppView> list(boolean unreadOnly, PageQuery query) {
    Specification<InboxItem> filter = Specs.equal("userUid", me());
    if (unreadOnly) {
      filter = filter.and((root, q, cb) -> cb.isNull(root.get("readAt")));
    }
    return Keyset.page(inbox, filter, query, Dispatcher::view);
  }

  @Override
  @Transactional(readOnly = true)
  public long unreadCount() {
    return inbox.countByUserUidAndReadAtIsNull(me());
  }

  @Override
  @Transactional
  public void markRead(UUID uid) {
    inbox
        .findByUidAndUserUid(uid, me())
        .orElseThrow(() -> Refusal.notFound("notification_not_found", "No such notification."))
        .markRead();
  }

  @Override
  @Transactional
  public void markAllRead() {
    inbox.markAllRead(me(), clock.instant());
  }

  private static UUID me() {
    UUID uid = ExecutionContext.actor().uid();
    if (uid == null) {
      throw Refusal.forbidden("forbidden", "Only a signed-in user has an inbox.");
    }
    return uid;
  }
}
