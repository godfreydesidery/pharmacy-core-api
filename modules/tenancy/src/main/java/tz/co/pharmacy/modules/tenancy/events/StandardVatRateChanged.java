package tz.co.pharmacy.modules.tenancy.events;

import java.math.BigDecimal;
import tz.co.pharmacy.platform.kernel.DomainEvent;
import tz.co.pharmacy.platform.kernel.EventMetadata;

/**
 * "settings.standard_vat_rate_changed": the pharmacy's standard VAT rate changed (its settings'
 * {@code defaultTaxRatePercent}); the catalogue sells every STANDARD product at it from now on.
 */
public record StandardVatRateChanged(EventMetadata metadata, BigDecimal ratePercent)
    implements DomainEvent {}
