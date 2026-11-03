/**
 * The checkout application service: the use cases of a POS sale that write to more than one module.
 * A sale prices its lines from the catalogue, takes the stock from inventory earliest expiry first,
 * and records the sale in sales, all in one transaction it opens: a refusal from any module leaves
 * none of the writes. It decides nothing a module owns, and imports only platform, the modules'
 * ports.inbound and its own packages.
 */
@ApplicationModule(displayName = "checkout")
package tz.co.pharmacy.application.checkout;

import org.springframework.modulith.ApplicationModule;
