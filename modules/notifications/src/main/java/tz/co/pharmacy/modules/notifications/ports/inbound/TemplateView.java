package tz.co.pharmacy.modules.notifications.ports.inbound;

/** A template in effect for one key, channel and locale; {@code custom} when the tenant's own. */
public record TemplateView(
    String key, Channel channel, String locale, String subject, String body, boolean custom) {}
