package tz.co.pharmacy.modules.receivables.ports.outbound;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/** The customer's name, credit limit and payment terms, from partners. */
public interface CustomerDirectory {

  Optional<CustomerFacts> customer(UUID customerUid);

  record CustomerFacts(String name, BigDecimal creditLimit, int termsDays, boolean active) {}
}
