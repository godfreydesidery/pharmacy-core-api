package tz.co.pharmacy.platform.kernel;

import java.util.UUID;

/**
 * Who is acting: a signed-in user or a named platform service. There is no anonymous actor that
 * writes. {@code reference} is recorded beside every change: the user's email, or {@code
 * service:<name>}.
 */
public record Actor(ActorType type, UUID uid, String name, String reference) {

  public static Actor user(UUID userUid, String name, String email) {
    return new Actor(ActorType.USER, userUid, name, email);
  }

  public static Actor platformService(String serviceName) {
    return new Actor(ActorType.PLATFORM_SERVICE, null, serviceName, "service:" + serviceName);
  }

  public enum ActorType {
    USER,
    PLATFORM_SERVICE
  }
}
