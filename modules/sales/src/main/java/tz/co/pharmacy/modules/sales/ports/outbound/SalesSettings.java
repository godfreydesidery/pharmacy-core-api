package tz.co.pharmacy.modules.sales.ports.outbound;

import java.math.BigDecimal;
import java.util.UUID;

/** The tenant's sales settings and its branches' codes and names (tenancy). */
public interface SalesSettings {

  String invoicePrefix();

  BigDecimal maxDiscountPercent();

  String branchCode(UUID branchUid);

  String branchName(UUID branchUid);
}
