package tz.co.pharmacy.support;

import java.util.concurrent.ThreadLocalRandom;

/** A person's phone set as a person sets it: the code sent by SMS typed back. */
public final class Phones {

  private Phones() {}

  /** A fresh Tanzanian mobile number, so tests never share one. */
  public static String fresh() {
    return "+2557" + (10_000_000 + ThreadLocalRandom.current().nextInt(89_999_999));
  }

  /** Sends a code to {@code phone} and verifies it with what the SMS said. */
  public static void verify(Api session, Outbox outbox, String phone) {
    session.post("/api/v1/auth/phone", "{\"phone\":\"%s\"}".formatted(phone)).expect(202);
    String code =
        Outbox.code(outbox.platformMessage("access.verification_code", "SMS", phone, true));
    session.post("/api/v1/auth/phone/verify", "{\"code\":\"%s\"}".formatted(code)).expect(200);
  }
}
