/**
 * Integrations with external systems: outbound webhooks that deliver domain events as signed
 * CloudEvents, and the record of every delivery.
 */
@ApplicationModule(displayName = "integrations")
package tz.co.pharmacy.modules.integrations;

import org.springframework.modulith.ApplicationModule;
