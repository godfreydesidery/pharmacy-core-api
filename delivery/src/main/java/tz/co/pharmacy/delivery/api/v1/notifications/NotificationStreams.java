package tz.co.pharmacy.delivery.api.v1.notifications;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tz.co.pharmacy.modules.notifications.events.InAppDelivered;
import tz.co.pharmacy.modules.notifications.ports.inbound.InAppView;

/**
 * The open Server-Sent Events streams, per tenant (or platform) and user. A new in-app message is
 * pushed as {@code notification} with the user's unread count; every 25 seconds a comment keeps
 * proxies from closing idle streams. Streams live on the node that opened them: with several nodes,
 * a shared channel (such as Redis pub/sub) would fan {@link InAppDelivered} out to all.
 */
@Component
public class NotificationStreams {

  private static final Duration TIMEOUT = Duration.ofMinutes(30);
  private static final int MAX_PER_USER = 5;

  /** A pushed event: the new unread count and the message that caused it, null on connect. */
  public record StreamEvent(long unreadCount, InAppView latest) {}

  private final Map<String, List<SseEmitter>> streams = new ConcurrentHashMap<>();

  /** The session each open stream belongs to, checked on every heartbeat. */
  private final Map<SseEmitter, UUID> sessions = new ConcurrentHashMap<>();

  private final tz.co.pharmacy.modules.access.ports.inbound.AuthenticationService authentication;

  public NotificationStreams(
      tz.co.pharmacy.modules.access.ports.inbound.AuthenticationService authentication) {
    this.authentication = authentication;
  }

  SseEmitter open(UUID tenantUid, UUID userUid, UUID sessionUid, long unreadCount) {
    SseEmitter emitter = new SseEmitter(TIMEOUT.toMillis());
    if (sessionUid != null) {
      sessions.put(emitter, sessionUid);
    }
    String key = key(tenantUid, userUid);
    List<SseEmitter> mine = streams.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
    while (mine.size() >= MAX_PER_USER) {
      mine.removeFirst().complete();
    }
    mine.add(emitter);
    Runnable forget = () -> remove(key, emitter);
    emitter.onCompletion(forget);
    emitter.onTimeout(forget);
    emitter.onError(error -> forget.run());
    send(key, emitter, "ready", new StreamEvent(unreadCount, null));
    return emitter;
  }

  @EventListener
  public void on(InAppDelivered delivered) {
    String key = key(delivered.tenantUid(), delivered.userUid());
    for (SseEmitter emitter : streams.getOrDefault(key, List.of())) {
      send(
          key,
          emitter,
          "notification",
          new StreamEvent(delivered.unreadCount(), delivered.latest()));
    }
  }

  @Scheduled(fixedDelay = 25_000, initialDelay = 25_000)
  void heartbeat() {
    streams.forEach(
        (key, emitters) -> {
          for (SseEmitter emitter : emitters) {
            UUID session = sessions.get(emitter);
            if (session != null && !authentication.sessionLive(session)) {
              // Signed out, revoked or idle: the stream ends with its session (AUTH-11).
              remove(key, emitter);
              emitter.complete();
              continue;
            }
            try {
              emitter.send(SseEmitter.event().comment("ping"));
            } catch (IOException | IllegalStateException gone) {
              remove(key, emitter);
            }
          }
        });
  }

  private void send(String key, SseEmitter emitter, String name, StreamEvent event) {
    try {
      emitter.send(SseEmitter.event().name(name).data(event, MediaType.APPLICATION_JSON));
    } catch (IOException | IllegalStateException gone) {
      remove(key, emitter);
    }
  }

  private void remove(String key, SseEmitter emitter) {
    sessions.remove(emitter);
    List<SseEmitter> mine = streams.get(key);
    if (mine != null) {
      mine.remove(emitter);
      if (mine.isEmpty()) {
        streams.remove(key, mine);
      }
    }
  }

  private static String key(UUID tenantUid, UUID userUid) {
    return (tenantUid == null ? "platform" : tenantUid.toString()) + ":" + userUid;
  }
}
