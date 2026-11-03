package tz.co.pharmacy.modules.notifications.ports.inbound;

import java.util.List;

/** The current user's notification preferences in the current tenant. */
public interface Preferences {

  /** Every category, with every channel where the user has chosen none. */
  List<PreferenceView> mine();

  /** 422 unknown_category; changes to {@code access} are ignored. Returns {@link #mine()}. */
  List<PreferenceView> change(List<PreferenceView> changes);
}
