package tz.co.pharmacy.modules.notifications.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** One in-app message in a user's inbox. */
@Getter
@Entity
@Table(schema = "notifications", name = "inbox")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InboxItem extends ScopedEntity {

  @Column(nullable = false)
  private UUID userUid;

  @Column(nullable = false)
  private String title;

  @Column(nullable = false)
  private String body;

  private String link;

  @Column(nullable = false)
  private String severity;

  private Instant readAt;

  public InboxItem(UUID userUid, String title, String body, String link, String severity) {
    this.userUid = userUid;
    this.title = title;
    this.body = body;
    this.link = link;
    this.severity = severity;
  }

  public void markRead() {
    if (readAt == null) {
      readAt = Instant.now();
    }
  }
}
