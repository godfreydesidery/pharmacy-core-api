package tz.co.pharmacy.modules.notifications.ports.inbound;

/** A tenant's own wording for a template; {@code subject} is ignored for SMS and WhatsApp. */
public record TemplateCommand(String subject, String body) {}
