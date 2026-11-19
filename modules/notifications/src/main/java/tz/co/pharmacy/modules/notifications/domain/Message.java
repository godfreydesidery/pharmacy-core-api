package tz.co.pharmacy.modules.notifications.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.modules.notifications.ports.inbound.Channel;
import tz.co.pharmacy.platform.kernel.Clocks;

/** One message to one address on one channel: the outbox row and its delivery record. */
@Getter
@Entity
@Table(schema = "notifications", name = "messages")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Message extends ScopedEntity {

  /** After this many attempts a message stays FAILED. */
  public static final int MAX_ATTEMPTS = 5;

  @Column(nullable = false)
  private String templateKey;

  @Column(nullable = false)
  private String category;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Channel channel;

  private UUID recipientUserUid;
  private String recipientName;

  @Column(nullable = false)
  private String toAddress;

  @Column(nullable = false)
  private String locale;

  private String subject;

  @Column(nullable = false)
  private String body;

  private String htmlBody;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private MessageStatus status;

  private String provider;

  @Column(nullable = false)
  private int attempts;

  private Instant nextAttemptAt;
  private String lastError;
  private Instant sentAt;
  private String dedupKey;

  @SuppressWarnings("java:S107")
  public Message(
      String templateKey,
      String category,
      Channel channel,
      UUID recipientUserUid,
      String recipientName,
      String toAddress,
      String locale,
      String subject,
      String body,
      String htmlBody,
      String dedupKey,
      Instant firstDueAt) {
    this.templateKey = templateKey;
    this.category = category;
    this.channel = channel;
    this.recipientUserUid = recipientUserUid;
    this.recipientName = recipientName;
    this.toAddress = toAddress;
    this.locale = locale;
    this.subject = subject;
    this.body = body;
    this.htmlBody = htmlBody;
    this.dedupKey = dedupKey;
    this.status = MessageStatus.QUEUED;
    this.nextAttemptAt = firstDueAt;
  }

  /** An in-app message is delivered the moment its inbox row is written. */
  public void deliveredInApp() {
    this.status = MessageStatus.SENT;
    this.provider = "in-app";
    this.attempts = 1;
    this.sentAt = Clocks.now();
    this.nextAttemptAt = null;
  }

  public void sent(String providerName) {
    this.attempts++;
    this.status = MessageStatus.SENT;
    this.provider = providerName;
    this.sentAt = Clocks.now();
    this.lastError = null;
    this.nextAttemptAt = null;
  }

  /** Records a failed attempt and schedules the next one, if any are left. */
  public void failed(String providerName, String error, Instant nextAttempt) {
    this.attempts++;
    this.status = MessageStatus.FAILED;
    this.provider = providerName;
    this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 500));
    this.nextAttemptAt = attempts >= MAX_ATTEMPTS ? null : nextAttempt;
  }
}
