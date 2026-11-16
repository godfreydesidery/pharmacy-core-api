package tz.co.pharmacy.modules.sales.events;

import java.math.BigDecimal;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.DomainEvent;
import tz.co.pharmacy.platform.kernel.EventMetadata;

/** {@code voucher.issued}: prepaid value was sold; the money came in by {@code paidBy}. */
public record VoucherIssued(
    EventMetadata metadata,
    UUID voucherUid,
    String code,
    UUID branchUid,
    BigDecimal value,
    String paidBy)
    implements DomainEvent {}
