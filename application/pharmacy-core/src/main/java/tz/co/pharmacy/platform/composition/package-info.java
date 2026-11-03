/**
 * Composition: the only place modules are bound together. Adapters implement one module's outbound
 * ports with another's inbound ports; reactions turn one module's events into calls on another's
 * inbound ports. Default integration adapters (payments, fiscal receipts) live here too, each
 * replaceable by a real provider through configuration.
 */
@ApplicationModule(displayName = "composition")
package tz.co.pharmacy.platform.composition;

import org.springframework.modulith.ApplicationModule;
