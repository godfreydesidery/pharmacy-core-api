package tz.co.pharmacy.modules.insurance.ports.outbound;

import java.util.Optional;
import java.util.UUID;

/** What insurance reads from other modules: customer names, the base currency, the pharmacy. */
public interface InsuranceDirectory {

  Optional<String> customerName(UUID customerUid);

  String currency();

  String providerName();
}
