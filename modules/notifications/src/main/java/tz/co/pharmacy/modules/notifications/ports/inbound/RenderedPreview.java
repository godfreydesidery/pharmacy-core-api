package tz.co.pharmacy.modules.notifications.ports.inbound;

import java.util.Map;

/**
 * A template rendered with sample parameters, as the template editor shows it: {@code html} for
 * email (null otherwise), and plain {@code text}.
 */
public record RenderedPreview(
    String subject, String text, String html, Map<String, Object> sampleParams) {}
