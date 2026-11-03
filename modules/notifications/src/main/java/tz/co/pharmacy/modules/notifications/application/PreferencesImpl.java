package tz.co.pharmacy.modules.notifications.application;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.notifications.domain.Preference;
import tz.co.pharmacy.modules.notifications.persistence.PreferenceRepository;
import tz.co.pharmacy.modules.notifications.ports.inbound.Channel;
import tz.co.pharmacy.modules.notifications.ports.inbound.PreferenceView;
import tz.co.pharmacy.modules.notifications.ports.inbound.Preferences;
import tz.co.pharmacy.modules.notifications.ports.inbound.TemplateKeys;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Refusal;

@Service
class PreferencesImpl implements Preferences {

  private final PreferenceRepository preferences;

  PreferencesImpl(PreferenceRepository preferences) {
    this.preferences = preferences;
  }

  @Override
  @Transactional(readOnly = true)
  public List<PreferenceView> mine() {
    Map<String, Preference> chosen =
        preferences.findByUserUid(me()).stream()
            .collect(Collectors.toMap(Preference::getCategory, Function.identity(), (a, b) -> a));
    return TemplateKeys.CATEGORIES.stream()
        .map(
            category ->
                new PreferenceView(
                    category,
                    "access".equals(category) || !chosen.containsKey(category)
                        ? EnumSet.allOf(Channel.class)
                        : chosen.get(category).channelSet()))
        .toList();
  }

  @Override
  @Transactional
  public List<PreferenceView> change(List<PreferenceView> changes) {
    UUID me = me();
    for (PreferenceView change : changes == null ? List.<PreferenceView>of() : changes) {
      if (change == null || !TemplateKeys.CATEGORIES.contains(change.category())) {
        throw Refusal.invalid("unknown_category", "No such notification category.", "category");
      }
      if ("access".equals(change.category())) {
        continue;
      }
      EnumSet<Channel> channels =
          change.channels() == null || change.channels().isEmpty()
              ? EnumSet.noneOf(Channel.class)
              : EnumSet.copyOf(change.channels());
      preferences
          .findByUserUidAndCategory(me, change.category())
          .ifPresentOrElse(
              existing -> existing.change(channels),
              () -> preferences.save(new Preference(me, change.category(), channels)));
    }
    preferences.flush();
    return mine();
  }

  private static UUID me() {
    UUID uid = ExecutionContext.actor().uid();
    if (uid == null) {
      throw Refusal.forbidden("forbidden", "Only a signed-in user has preferences.");
    }
    return uid;
  }
}
