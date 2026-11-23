package tz.co.pharmacy.delivery.api.v1.notifications;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tz.co.pharmacy.modules.access.ports.inbound.AuthenticationService;
import tz.co.pharmacy.modules.notifications.events.InAppDelivered;
import tz.co.pharmacy.modules.notifications.ports.inbound.InAppInbox;
import tz.co.pharmacy.modules.notifications.ports.inbound.InAppSnapshot;
import tz.co.pharmacy.modules.notifications.ports.inbound.InAppView;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.persistence.InstanceName;
import tz.co.pharmacy.platform.persistence.TenantContext;
import tz.co.pharmacy.platform.persistence.signals.PostgresSignals;

/**
 * The open Server-Sent Events streams of this instance, per tenant (or platform) and user. A new
 * in-app message is pushed as {@code notification} with the user's unread count; every 25 seconds a
 * comment keeps proxies from closing idle streams.
 *
 * <p>A stream lives on the instance that opened it, but a message may be created on any instance.
 * So every new message is also signalled to the others through PostgreSQL {@code NOTIFY} on {@value
 * #CHANNEL} ({@link PostgresSignals}): the payload names the sending instance, the tenant and the
 * user, nothing else. An instance with a stream open for that user reads the user's latest message
 * and unread count from the database and pushes them. When the listener connection was lost and is
 * back, every open stream gets its current state again, since signals sent meanwhile are lost.
 */
@Component
public class NotificationStreams {

  /** The PostgreSQL channel new in-app messages are signalled on. */
  public static final String CHANNEL = "pharmacy_inapp";

  private static final Logger log = LoggerFactory.getLogger(NotificationStreams.class);
  private static final Duration TIMEOUT = Duration.ofMinutes(30);
  private static final int MAX_PER_USER = 5;
  private static final String PLATFORM = "platform";
  private static final String SERVICE = "notification-stream";

  /** A pushed event: the new unread count and the message that caused it, null on connect. */
  public record StreamEvent(long unreadCount, InAppView latest) {}

  private final Map<String, List<SseEmitter>> streams = new ConcurrentHashMap<>();

  /** The session each open stream belongs to, checked on every heartbeat. */
  private final Map<SseEmitter, UUID> sessions = new ConcurrentHashMap<>();

  private final AuthenticationService authentication;
  private final InAppInbox inbox;
  private final PostgresSignals signals;
  private final InstanceName instance;

  /** Reads for signals from other instances, off the listener thread. */
  private final ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor();

  public NotificationStreams(
      AuthenticationService authentication,
      InAppInbox inbox,
      PostgresSignals signals,
      InstanceName instance) {
    this.authentication = authentication;
    this.inbox = inbox;
    this.signals = signals;
    this.instance = instance;
    signals.subscribe(
        CHANNEL,
        new PostgresSignals.Subscriber() {
          @Override
          public void received(String payload) {
            signalled(payload);
          }

          @Override
          public void reconnected() {
            refreshAll();
          }
        });
  }

  public SseEmitter open(UUID tenantUid, UUID userUid, UUID sessionUid, long unreadCount) {
    SseEmitter emitter = newEmitter(TIMEOUT.toMillis());
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

  /** A new emitter (tests record what is sent through it). */
  protected SseEmitter newEmitter(long timeoutMillis) {
    return new SseEmitter(timeoutMillis);
  }

  /** A message was delivered on this instance: pushed here, and signalled to the others. */
  @EventListener
  public void on(InAppDelivered delivered) {
    push(
        key(delivered.tenantUid(), delivered.userUid()),
        new StreamEvent(delivered.unreadCount(), delivered.latest()));
    try {
      signals.publish(
          CHANNEL,
          instance.get()
              + "|"
              + (delivered.tenantUid() == null ? PLATFORM : delivered.tenantUid())
              + "|"
              + delivered.userUid());
    } catch (RuntimeException failure) {
      // Streams on other instances catch up at their client's next read.
      log.warn("Could not signal a new in-app message: {}", failure.toString());
    }
  }

  /** A signal from an instance: "instance|tenant-or-platform|user". */
  void signalled(String payload) {
    String[] parts = payload == null ? new String[0] : payload.split("\\|", -1);
    if (parts.length != 3 || instance.get().equals(parts[0])) {
      return; // malformed, or ours (already pushed)
    }
    UUID tenantUid;
    UUID userUid;
    try {
      tenantUid = PLATFORM.equals(parts[1]) ? null : UUID.fromString(parts[1]);
      userUid = UUID.fromString(parts[2]);
    } catch (IllegalArgumentException malformed) {
      return;
    }
    String key = key(tenantUid, userUid);
    if (streams.containsKey(key)) {
      readers.submit(() -> refresh(tenantUid, userUid));
    }
  }

  /** Every open stream gets its user's current state (after the listener reconnected). */
  void refreshAll() {
    for (String key : streams.keySet()) {
      int colon = key.indexOf(':');
      String tenant = key.substring(0, colon);
      UUID tenantUid = PLATFORM.equals(tenant) ? null : UUID.fromString(tenant);
      UUID userUid = UUID.fromString(key.substring(colon + 1));
      readers.submit(() -> refresh(tenantUid, userUid));
    }
  }

  private void refresh(UUID tenantUid, UUID userUid) {
    try {
      InAppSnapshot snapshot =
          ExecutionContext.callAs(
              ExecutionContext.Frame.service(SERVICE, null, tenantUid),
              () -> TenantContext.callAs(tenantUid, () -> inbox.snapshot(userUid)));
      if (snapshot != null && snapshot.latest() != null) {
        push(key(tenantUid, userUid), new StreamEvent(snapshot.unreadCount(), snapshot.latest()));
      }
    } catch (RuntimeException failure) {
      log.warn("Could not refresh a notification stream: {}", failure.toString());
    }
  }

  private void push(String key, StreamEvent event) {
    for (SseEmitter emitter : streams.getOrDefault(key, List.of())) {
      send(key, emitter, "notification", event);
    }
  }

  /** Per instance: the streams of this instance (others ping their own). */
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
    return (tenantUid == null ? PLATFORM : tenantUid.toString()) + ":" + userUid;
  }
}
