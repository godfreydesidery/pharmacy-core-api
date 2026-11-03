package tz.co.pharmacy.delivery.api.v1.notifications;

import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.delivery.api.Pages;
import tz.co.pharmacy.modules.notifications.ports.inbound.InAppInbox;
import tz.co.pharmacy.modules.notifications.ports.inbound.InAppView;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Page;

/**
 * The signed-in user's in-app notifications, in the tenant the request names (or at platform scope
 * without one), and their live stream.
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationHandler {

  private final InAppInbox inbox;
  private final NotificationStreams streams;

  public NotificationHandler(InAppInbox inbox, NotificationStreams streams) {
    this.inbox = inbox;
    this.streams = streams;
  }

  public record UnreadCount(long count) {}

  @GetMapping
  @Access(scope = AccessScope.AUTHENTICATED)
  public ApiResponse<Page<InAppView>> list(
      @RequestParam(defaultValue = "false") boolean unreadOnly,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    return ApiResponse.ok(
        inbox.list(unreadOnly, Pages.of(cursor, limit)), "Notifications retrieved");
  }

  @GetMapping("/unread-count")
  @Access(scope = AccessScope.AUTHENTICATED)
  public ApiResponse<UnreadCount> unreadCount() {
    return ApiResponse.ok(new UnreadCount(inbox.unreadCount()), "Unread count retrieved");
  }

  @PostMapping("/{uid}/read")
  @Access(scope = AccessScope.AUTHENTICATED)
  public ApiResponse<UnreadCount> read(@PathVariable UUID uid) {
    inbox.markRead(uid);
    return ApiResponse.ok(new UnreadCount(inbox.unreadCount()), "Notification read");
  }

  @PostMapping("/read-all")
  @Access(scope = AccessScope.AUTHENTICATED)
  public ApiResponse<UnreadCount> readAll() {
    inbox.markAllRead();
    return ApiResponse.ok(new UnreadCount(0), "All notifications read");
  }

  /**
   * Server-Sent Events: {@code ready} with the unread count on connect, then {@code notification}
   * with the new count and message whenever one arrives. EventSource cannot send headers, so this
   * path also takes {@code ?token=} and {@code ?tenant=}.
   */
  @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @Access(scope = AccessScope.AUTHENTICATED)
  public SseEmitter stream() {
    return streams.open(
        ExecutionContext.tenantUid().orElse(null),
        ExecutionContext.actor().uid(),
        inbox.unreadCount());
  }
}
