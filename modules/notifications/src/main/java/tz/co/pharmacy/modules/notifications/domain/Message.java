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

  /**
   * Why the message was not sent, as a stable code, when the platform refused it rather than a
   * provider failing (such as {@code sms_cap_reached}); null otherwise.
   */
  private String reason;

  /**
   * The body (and HTML body) are sealed with the keyring: an account message waiting to be sent
   * (AUTH-8). Opened only to send it.
   */
  private boolean sealed;

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

  /**
   * An account message (reset or invitation link, sign-in or verification code) has been sent: its
   * text is not kept, so the outbox, a backup or a data export never holds a live secret (OWASP: do
   * not store what you do not need; adversarial review AUTH-8, ISO-3).
   */
  public void forgetText() {
    this.body = "(Sent. Account messages are not kept: they carry links and codes.)";
    this.htmlBody = null;
    this.sealed = false;
  }

  /** An account message that will not be sent: its link or code goes too (AUTH-8). */
  public void forgetUnsent() {
    this.body = "(Not sent. Account messages are not kept: they carry links and codes.)";
    this.htmlBody = null;
    this.sealed = false;
  }

  /** Seals the text while the message waits to be sent; once only. */
  public void seal(java.util.function.UnaryOperator<String> sealer) {
    if (sealed) {
      return;
    }
    this.body = sealer.apply(body);
    this.htmlBody = htmlBody == null ? null : sealer.apply(htmlBody);
    this.sealed = true;
  }

  /** The text to send: opened when sealed. */
  public String plainBody(java.util.function.UnaryOperator<String> opener) {
    return sealed ? opener.apply(body) : body;
  }

  /** The HTML text to send, if any: opened when sealed. */
  public String plainHtmlBody(java.util.function.UnaryOperator<String> opener) {
    return sealed && htmlBody != null ? opener.apply(htmlBody) : htmlBody;
  }

  /** Keeps the text in the clear (local and test runs, which read links from the outbox). */
  public void keepText(String plainBody, String plainHtmlBody) {
    this.body = plainBody;
    this.htmlBody = plainHtmlBody;
    this.sealed = false;
  }

  /** An account message: links and codes that let someone into an account. */
  public boolean carriesSecrets() {
    return "access".equals(category);
  }

  public void sent(String providerName) {
    this.attempts++;
    this.status = MessageStatus.SENT;
    this.provider = providerName;
    this.sentAt = Clocks.now();
    this.lastError = null;
    this.reason = null;
    this.nextAttemptAt = null;
  }

  /** Records a failed attempt and schedules the next one, if any are left. */
  public void failed(String providerName, String error, Instant nextAttempt) {
    this.attempts++;
    this.status = MessageStatus.FAILED;
    this.provider = providerName;
    this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 500));
    this.reason = null;
    this.nextAttemptAt = attempts >= MAX_ATTEMPTS ? null : nextAttempt;
    if (nextAttemptAt == null && carriesSecrets()) {
      // No attempt left: the link or code would only wait in the outbox (AUTH-8).
      forgetUnsent();
    }
  }

  /**
   * Not sent, and not to be retried, because the platform refused it ({@code reason}, such as
   * {@code sms_cap_reached}); {@code error} says why in words.
   */
  public void refused(String providerName, String reason, String error) {
    this.attempts++;
    this.status = MessageStatus.FAILED;
    this.provider = providerName;
    this.reason = reason;
    this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 500));
    this.nextAttemptAt = null;
    if (carriesSecrets()) {
      forgetUnsent();
    }
  }
}
