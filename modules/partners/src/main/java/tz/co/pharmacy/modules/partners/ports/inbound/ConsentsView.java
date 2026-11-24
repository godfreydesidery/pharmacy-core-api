package tz.co.pharmacy.modules.partners.ports.inbound;

import java.time.Instant;
import java.util.List;

/** A customer's consents: the state per purpose, and every record, newest first. */
public record ConsentsView(List<Current> current, List<ConsentView> history) {

  /**
   * {@code status} GIVEN, WITHDRAWN or NONE (never asked); {@code since} when it became so; {@code
   * effective} whether messages of the purpose may be sent ({@link
   * ConsentPurpose#allowedWithoutRecord()} when NONE).
   */
  public record Current(ConsentPurpose purpose, String status, Instant since, boolean effective) {}
}
