package tz.co.pharmacy.modules.integrations.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Arrays;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/** An external endpoint that receives the tenant's events of the types it subscribes to. */
@Getter
@Entity
@Table(schema = "integrations", name = "webhooks")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Webhook extends TenantEntity {

  @Column(nullable = false)
  private String name;

  @Column(nullable = false)
  private String url;

  @Column(nullable = false)
  private String secret;

  /** The subscribed event types, comma-joined. */
  @Column(nullable = false)
  private String eventTypes;

  @Column(nullable = false)
  private boolean active;

  public Webhook(String name, String url, String secret, List<String> eventTypes, boolean active) {
    this.secret = secret;
    change(name, url, eventTypes, active);
  }

  public void change(String name, String url, List<String> eventTypes, boolean active) {
    this.name = name;
    this.url = url;
    this.eventTypes = String.join(",", eventTypes);
    this.active = active;
  }

  public List<String> eventTypeList() {
    return eventTypes.isBlank() ? List.of() : Arrays.asList(eventTypes.split(","));
  }

  public boolean subscribesTo(String eventType) {
    return active && eventTypeList().contains(eventType);
  }
}
