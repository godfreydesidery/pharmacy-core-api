package tz.co.pharmacy.modules.notifications.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.modules.notifications.ports.inbound.Channel;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/** A tenant's own wording for one template key, channel and locale. */
@Getter
@Entity
@Table(schema = "notifications", name = "templates")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TemplateOverride extends TenantEntity {

  @Column(nullable = false)
  private String templateKey;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Channel channel;

  @Column(nullable = false)
  private String locale;

  private String subject;

  @Column(nullable = false)
  private String body;

  public TemplateOverride(
      String templateKey, Channel channel, String locale, String subject, String body) {
    this.templateKey = templateKey;
    this.channel = channel;
    this.locale = locale;
    change(subject, body);
  }

  public void change(String subject, String body) {
    this.subject = subject;
    this.body = body;
  }
}
