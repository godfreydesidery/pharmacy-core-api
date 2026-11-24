package tz.co.pharmacy.modules.tenancy.ports.inbound;

import java.math.BigDecimal;

/**
 * One band of the expiry write-down rule (STD-3): stock whose expiry is at most {@code
 * daysToExpiry} days after the month end (0: already expired) is written down by {@code percent} of
 * its cost. A batch takes the first band it falls in, bands ordered by days.
 */
public record ProvisionBand(int daysToExpiry, BigDecimal percent) {}
