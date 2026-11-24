package tz.co.pharmacy.platform.composition;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.finance.ports.inbound.SupplierPayments;
import tz.co.pharmacy.modules.purchasing.ports.outbound.PayableSettlements;

/**
 * Binds purchasing's view of what payments settled of its receipts to finance. Finance's payments
 * read purchasing's credited returns in turn, so the payments are looked up when first needed.
 */
@Component
class PayableSettlementsAdapter implements PayableSettlements {

  private final ObjectProvider<SupplierPayments> payments;

  PayableSettlementsAdapter(ObjectProvider<SupplierPayments> payments) {
    this.payments = payments;
  }

  @Override
  public Map<UUID, BigDecimal> settledByPayments(Collection<UUID> receiptUids) {
    return payments.getObject().settledOf(receiptUids);
  }
}
