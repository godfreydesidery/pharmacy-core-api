package tz.co.pharmacy.modules.sales.ports.outbound;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Issues the legal fiscal receipt of a sale with the tax authority, such as TRA VFD in Tanzania,
 * and returns its reference. The default adapter issues none and returns empty.
 */
public interface FiscalReceipts {

  Optional<String> issue(String saleNumber, BigDecimal total, BigDecimal taxTotal);
}
