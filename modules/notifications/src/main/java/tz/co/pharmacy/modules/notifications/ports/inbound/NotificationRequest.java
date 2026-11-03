package tz.co.pharmacy.modules.notifications.ports.inbound;

import java.util.Map;
import java.util.Set;

/**
 * One notification to send: a template key such as {@code stock.low}, its recipient, the channels
 * to try and the template's parameters. A non-null {@code dedupKey} sends it at most once per
 * channel and address within 24 hours.
 */
public record NotificationRequest(
    String templateKey,
    Recipient recipient,
    Set<Channel> channels,
    Map<String, Object> params,
    String dedupKey) {}
