package tz.co.pharmacy.modules.notifications.application;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.pharmacy.modules.notifications.domain.MessageStatus;
import tz.co.pharmacy.modules.notifications.persistence.MessageRepository;
import tz.co.pharmacy.modules.notifications.ports.inbound.Channel;
import tz.co.pharmacy.modules.notifications.ports.inbound.NotificationUsage;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.persistence.TenantContext;

/**
 * Counts from the delivery log, read in the tenant's own scope in a transaction of its own (row-
 * level security shows a tenant's messages only to that tenant), so any caller may ask.
 */
@Service
class NotificationUsageImpl implements NotificationUsage {

  private final MessageRepository messages;
  private final TransactionTemplate reads;
  private final Clock clock;

  NotificationUsageImpl(
      MessageRepository messages, PlatformTransactionManager transactionManager, Clock clock) {
    this.messages = messages;
    this.clock = clock;
    this.reads = new TransactionTemplate(transactionManager);
    this.reads.setReadOnly(true);
    this.reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Override
  public long sentThisMonth(UUID tenantUid, Channel channel) {
    return sentIn(tenantUid, channel, YearMonth.now(ZoneOffset.UTC));
  }

  @Override
  public long platformSmsThisMonth(UUID tenantUid, ZoneId zone) {
    UUID tenant = Checks.required(tenantUid, "tenantUid");
    SmsCap.Window window = SmsCap.Window.of(clock.instant(), zone == null ? ZoneOffset.UTC : zone);
    Long count =
        TenantContext.callAs(
            tenant,
            () ->
                reads.execute(
                    status -> messages.countPlatformSmsSent(tenant, window.from(), window.to())));
    return count == null ? 0 : count;
  }

  @Override
  public long sentIn(UUID tenantUid, Channel channel, YearMonth month) {
    return usage(tenantUid, month).getOrDefault(Checks.required(channel, "channel"), 0L);
  }

  @Override
  public Map<Channel, Long> usage(UUID tenantUid, YearMonth month) {
    UUID tenant = Checks.required(tenantUid, "tenantUid");
    YearMonth m = month == null ? YearMonth.now(ZoneOffset.UTC) : month;
    Instant from = m.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
    Instant to = m.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
    return TenantContext.callAs(
        tenant,
        () ->
            reads.execute(
                status -> {
                  Map<Channel, Long> counts = new EnumMap<>(Channel.class);
                  for (Channel channel : Channel.values()) {
                    counts.put(
                        channel,
                        messages.countByChannelAndStatusAndSentAtGreaterThanEqualAndSentAtLessThan(
                            channel, MessageStatus.SENT, from, to));
                  }
                  return counts;
                }));
  }
}
