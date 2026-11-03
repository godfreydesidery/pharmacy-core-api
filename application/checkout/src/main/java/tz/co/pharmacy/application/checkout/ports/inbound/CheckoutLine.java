package tz.co.pharmacy.application.checkout.ports.inbound;

import java.util.List;
import java.util.UUID;

/**
 * One product and quantity in the POS cart. For a serial-tracked product (a wheelchair) {@code
 * serialNumbers} name the units sold, one per unit; required when the product asks for its serial
 * at sale.
 */
public record CheckoutLine(UUID productUid, int quantity, List<String> serialNumbers) {}
