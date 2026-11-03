package tz.co.pharmacy.modules.integrations.ports.inbound;

import java.util.List;

public record WebhookCommand(String name, String url, List<String> eventTypes, Boolean active) {}
