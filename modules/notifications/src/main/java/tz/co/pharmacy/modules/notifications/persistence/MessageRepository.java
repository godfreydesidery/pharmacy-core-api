package tz.co.pharmacy.modules.notifications.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tz.co.pharmacy.modules.notifications.domain.Message;
import tz.co.pharmacy.modules.notifications.ports.inbound.Channel;

public interface MessageRepository
    extends JpaRepository<Message, Long>, JpaSpecificationExecutor<Message> {

  Optional<Message> findByUid(UUID uid);

  boolean existsByDedupKeyAndChannelAndToAddressAndCreatedAtAfter(
      String dedupKey, Channel channel, String toAddress, Instant after);

  /**
   * Claims a message for one sender: a QUEUED message at once, a FAILED or SENDING one (whose
   * sender's lease ran out) once it is due. Returns 1 when this caller won the claim.
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update Message m set m.status = tz.co.pharmacy.modules.notifications.domain.MessageStatus.SENDING,"
          + " m.nextAttemptAt = :lease"
          + " where m.uid = :uid and m.attempts < :maxAttempts"
          + " and (m.status = tz.co.pharmacy.modules.notifications.domain.MessageStatus.QUEUED"
          + " or ((m.status = tz.co.pharmacy.modules.notifications.domain.MessageStatus.FAILED"
          + " or m.status = tz.co.pharmacy.modules.notifications.domain.MessageStatus.SENDING)"
          + " and m.nextAttemptAt <= :now))")
  int claim(
      @Param("uid") UUID uid,
      @Param("now") Instant now,
      @Param("lease") Instant lease,
      @Param("maxAttempts") int maxAttempts);

  @Query(
      "select m.uid from Message m where m.attempts < :maxAttempts and m.nextAttemptAt <= :now"
          + " and m.status <> tz.co.pharmacy.modules.notifications.domain.MessageStatus.SENT"
          + " order by m.nextAttemptAt")
  List<UUID> due(@Param("now") Instant now, @Param("maxAttempts") int maxAttempts, Pageable page);

  @Query(
      "select min(m.nextAttemptAt) from Message m where m.attempts < :maxAttempts"
          + " and m.nextAttemptAt is not null"
          + " and m.status <> tz.co.pharmacy.modules.notifications.domain.MessageStatus.SENT")
  Instant earliestDue(@Param("maxAttempts") int maxAttempts);

  /**
   * The latest messages of the channel in the given status, newest first, for a channel's health.
   * Ordered by when they last changed: a message written once (a provider test) has no update time,
   * so ordering by it alone put such rows first in any order and showed a stale failure.
   */
  @Query(
      "select m from Message m where m.channel = :channel and m.status = :status"
          + " order by coalesce(m.updatedAt, m.createdAt) desc, m.id desc")
  List<Message> latestByStatus(
      @Param("channel") Channel channel,
      @Param("status") tz.co.pharmacy.modules.notifications.domain.MessageStatus status,
      Pageable page);

  default Optional<Message> latestByStatus(
      Channel channel, tz.co.pharmacy.modules.notifications.domain.MessageStatus status) {
    return latestByStatus(channel, status, Pageable.ofSize(1)).stream().findFirst();
  }

  Optional<Message> findFirstByChannelAndStatusOrderBySentAtDesc(
      Channel channel, tz.co.pharmacy.modules.notifications.domain.MessageStatus status);

  long countByChannelAndStatusAndSentAtGreaterThanEqualAndSentAtLessThan(
      Channel channel,
      tz.co.pharmacy.modules.notifications.domain.MessageStatus status,
      Instant from,
      Instant to);

  /**
   * The pharmacy's SMS sent on the platform's account between {@code from} and {@code to}: through
   * the platform's providers or the environment's, never its own (a provider label whose second
   * part is {@code own}, such as {@code beem:own} or {@code log:own:Our SMS}).
   */
  @Query(
      value =
          "select count(*) from notifications.messages m"
              + " where m.tenant_uid = :tenant and m.channel = 'SMS' and m.status = 'SENT'"
              + " and m.sent_at >= :fromAt and m.sent_at < :toAt"
              + " and split_part(coalesce(m.provider, ''), ':', 2) <> 'own'",
      nativeQuery = true)
  long countPlatformSmsSent(
      @Param("tenant") UUID tenant, @Param("fromAt") Instant from, @Param("toAt") Instant to);

  /**
   * The pharmacy's SMS another sender is sending right now (claimed, lease not run out), other than
   * {@code self}: they count toward the cap, so concurrent senders cannot pass it together.
   */
  @Query(
      value =
          "select count(*) from notifications.messages m"
              + " where m.tenant_uid = :tenant and m.channel = 'SMS' and m.status = 'SENDING'"
              + " and m.next_attempt_at > :now and m.uid <> :self",
      nativeQuery = true)
  long countSmsInFlight(
      @Param("tenant") UUID tenant, @Param("now") Instant now, @Param("self") UUID self);

  boolean existsByTemplateKeyAndDedupKey(String templateKey, String dedupKey);

  /**
   * Takes a transaction-scoped PostgreSQL advisory lock named {@code key}: one pharmacy's SMS cap
   * is checked by one sender at a time, on every instance.
   */
  @Query(
      value = "select count(*) from (select pg_advisory_xact_lock(hashtextextended(:key, 0))) l",
      nativeQuery = true)
  long lock(@Param("key") String key);
}
