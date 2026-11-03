/**
 * Notifications: email, SMS, WhatsApp and in-app messages from templates (built-in English and
 * Swahili, overridable per tenant), sent through swappable providers from an outbox with retries,
 * with per-user preferences, a delivery log and live in-app updates.
 */
@ApplicationModule(displayName = "notifications")
package tz.co.pharmacy.modules.notifications;

import org.springframework.modulith.ApplicationModule;
