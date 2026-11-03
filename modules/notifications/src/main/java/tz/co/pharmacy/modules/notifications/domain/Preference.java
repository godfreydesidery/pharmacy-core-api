package tz.co.pharmacy.modules.notifications.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.modules.notifications.ports.inbound.Channel;
import tz.co.pharmacy.platform.persistence.TenantEntity;

/** The channels one user chose for one category, as channel names joined by commas. */
@Getter
@Entity
@Table(schema = "notifications", name = "preferences")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Preference extends TenantEntity {

  @Column(nullable = false)
  private UUID userUid;

  @Column(nullable = false)
  private String category;

  @Column(nullable = false)
  private String channels;

  public Preference(UUID userUid, String category, Set<Channel> channels) {
    this.userUid = userUid;
    this.category = category;
    change(channels);
  }

  public void change(Set<Channel> chosen) {
    this.channels = chosen.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
  }

  public Set<Channel> channelSet() {
    if (channels == null || channels.isBlank()) {
      return EnumSet.noneOf(Channel.class);
    }
    return Arrays.stream(channels.split(","))
        .map(Channel::valueOf)
        .collect(Collectors.toCollection(() -> EnumSet.noneOf(Channel.class)));
  }
}
