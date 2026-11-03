package tz.co.pharmacy.modules.sales.ports.inbound;

import java.util.List;
import java.util.UUID;

/**
 * Units of one sale line coming back. For a line of serial-tracked units, {@code serialNumbers}
 * name the units returned; with none, the first not yet returned are.
 */
public record ReturnLineCommand(UUID saleLineUid, int quantity, List<String> serialNumbers) {}
