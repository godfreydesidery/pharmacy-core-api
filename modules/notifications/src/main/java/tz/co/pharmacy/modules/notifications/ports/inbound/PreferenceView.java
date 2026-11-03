package tz.co.pharmacy.modules.notifications.ports.inbound;

import java.util.Set;

/**
 * The channels a user receives one category of notification on, such as {@code stock}. The {@code
 * access} category (password reset, sign-in codes) cannot be turned off.
 */
public record PreferenceView(String category, Set<Channel> channels) {}
