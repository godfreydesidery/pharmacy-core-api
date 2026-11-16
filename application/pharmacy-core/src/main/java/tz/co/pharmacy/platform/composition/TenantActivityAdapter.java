package tz.co.pharmacy.platform.composition;

import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.finance.ports.inbound.Ledger;
import tz.co.pharmacy.modules.tenancy.ports.outbound.TenantActivity;

/** Binds the tenancy's "has it traded" check to the finance ledger. */
@Component
class TenantActivityAdapter implements TenantActivity {

  private final Ledger ledger;

  TenantActivityAdapter(Ledger ledger) {
    this.ledger = ledger;
  }

  @Override
  public boolean hasPostedTransactions() {
    return ledger.hasEntries();
  }
}
