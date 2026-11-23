package tz.co.pharmacy.modules.notifications.providers;

import java.util.List;
import java.util.Optional;
import tz.co.pharmacy.modules.notifications.ports.inbound.Channel;

/** The providers each channel can use, and the fields each needs (secret ones sealed at rest). */
public final class ProviderCatalog {

  /** A setting of a provider; a secret one is never shown back, only masked. */
  public record Field(String key, boolean secret, boolean required) {}

  public record Definition(Channel channel, String code, List<Field> fields) {

    public List<Field> plain() {
      return fields.stream().filter(f -> !f.secret()).toList();
    }

    public List<Field> secrets() {
      return fields.stream().filter(Field::secret).toList();
    }
  }

  private static final List<Definition> ALL =
      List.of(
          new Definition(Channel.EMAIL, "log", List.of()),
          new Definition(
              Channel.EMAIL,
              "smtp",
              List.of(
                  new Field("host", false, true),
                  new Field("port", false, true),
                  new Field("username", false, false),
                  new Field("from", false, true),
                  new Field("password", true, false))),
          new Definition(Channel.SMS, "log", List.of()),
          new Definition(
              Channel.SMS,
              "africastalking",
              List.of(
                  new Field("username", false, true),
                  new Field("senderId", false, false),
                  new Field("apiKey", true, true))),
          new Definition(
              Channel.SMS,
              "twilio",
              List.of(
                  new Field("accountSid", false, true),
                  new Field("from", false, true),
                  new Field("authToken", true, true))),
          new Definition(
              Channel.SMS,
              "beem",
              List.of(
                  new Field("senderId", false, true),
                  new Field("apiKey", true, true),
                  new Field("secretKey", true, true))),
          new Definition(Channel.WHATSAPP, "log", List.of()),
          new Definition(
              Channel.WHATSAPP,
              "twilio",
              List.of(
                  new Field("accountSid", false, true),
                  new Field("from", false, true),
                  new Field("authToken", true, true))));

  private ProviderCatalog() {}

  public static List<Definition> of(Channel channel) {
    return ALL.stream().filter(d -> d.channel() == channel).toList();
  }

  public static Optional<Definition> find(Channel channel, String code) {
    return ALL.stream()
        .filter(d -> d.channel() == channel && d.code().equalsIgnoreCase(code == null ? "" : code))
        .findFirst();
  }
}
