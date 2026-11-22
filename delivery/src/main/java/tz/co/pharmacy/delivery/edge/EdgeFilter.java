package tz.co.pharmacy.delivery.edge;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tz.co.pharmacy.modules.access.ports.inbound.AuthenticationService;
import tz.co.pharmacy.modules.access.ports.inbound.ClientInfo;
import tz.co.pharmacy.modules.access.ports.inbound.MembershipGrant;
import tz.co.pharmacy.modules.access.ports.inbound.MembershipResolver;
import tz.co.pharmacy.modules.access.ports.inbound.PharmacyVisits;
import tz.co.pharmacy.modules.access.ports.inbound.Principal;
import tz.co.pharmacy.modules.tenancy.ports.inbound.BranchService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.BranchView;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantStatus;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantView;
import tz.co.pharmacy.platform.kernel.Actor;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.ExecutionContext.Frame;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.kernel.Uids;
import tz.co.pharmacy.platform.persistence.TenantContext;

/**
 * The edge of every {@code /api} request. It sets the correlation id, authenticates the bearer
 * session, and resolves the tenant ({@code X-Tenant-Id}) and branch ({@code X-Branch-Id}) the
 * request acts in: a member must hold an active membership in the tenant and may use only its
 * branches; a root administrator may act in any tenant and branch. It then sets {@link
 * ExecutionContext} and {@link TenantContext} for the request. Which routes need what is enforced
 * afterwards by {@link AccessInterceptor}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class EdgeFilter extends OncePerRequestFilter {

  public static final String TENANT_HEADER = "X-Tenant-Id";
  public static final String BRANCH_HEADER = "X-Branch-Id";
  public static final String CORRELATION_HEADER = "X-Correlation-Id";

  /** The live notification stream (Server-Sent Events). */
  public static final String STREAM_PATH = "/api/v1/notifications/stream";

  /** The request attribute holding the authenticated {@link Principal}, if any. */
  public static final String PRINCIPAL = EdgeFilter.class.getName() + ".principal";

  /** The public sign-in routes, throttled per client IP. */
  static final String AUTH_PREFIX = "/api/v1/auth/";

  private static final Duration AUTH_WINDOW = Duration.ofMinutes(1);

  private final AuthenticationService authentication;
  private final MembershipResolver memberships;
  private final TenantService tenants;
  private final BranchService branches;
  private final RateLimiter rateLimiter;
  private final RootActivity rootActivity;
  private final PharmacyVisits visits;
  private final TenantConcurrency fairShare;
  private final int authRequestsPerMinute;

  public EdgeFilter(
      AuthenticationService authentication,
      MembershipResolver memberships,
      TenantService tenants,
      BranchService branches,
      RateLimiter rateLimiter,
      RootActivity rootActivity,
      PharmacyVisits visits,
      TenantConcurrency fairShare,
      @Value("${pharmacy.edge.auth-requests-per-minute:20}") int authRequestsPerMinute) {
    this.fairShare = fairShare;
    this.authentication = authentication;
    this.memberships = memberships;
    this.tenants = tenants;
    this.branches = branches;
    this.rateLimiter = rateLimiter;
    this.rootActivity = rootActivity;
    this.visits = visits;
    this.authRequestsPerMinute = authRequestsPerMinute;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/api/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String correlationId = correlationId(request);
    response.setHeader(CORRELATION_HEADER, correlationId);
    SecurityHeaders.apply(request, response);
    MDC.put("correlationId", correlationId);
    try {
      throttleSignIn(request);
      Frame frame = resolve(request, correlationId);
      ExecutionContext.enter(frame);
      TenantContext.enter(frame.tenantUid(), frame.actor() == null ? null : frame.actor().uid());
      if (frame.rootAdmin() && frame.tenantUid() != null && frame.actor() != null) {
        rootActivity.note(
            frame.actor().uid(), frame.tenantUid(), request.getMethod(), request.getRequestURI());
      }
      // Fair share: a pharmacy's own requests count against its limit (not the live stream, which
      // stays open, nor a root administrator's support work).
      if (frame.tenantUid() != null
          && !frame.rootAdmin()
          && !STREAM_PATH.equals(request.getRequestURI())) {
        try (TenantConcurrency.Slot slot = fairShare.acquire(frame.tenantUid())) {
          chain.doFilter(request, response);
        }
      } else {
        chain.doFilter(request, response);
      }
    } catch (Refusal refusal) {
      Problems.write(response, refusal, correlationId);
    } finally {
      ExecutionContext.leave();
      TenantContext.leave();
      MDC.remove("correlationId");
    }
  }

  private Frame resolve(HttpServletRequest request, String correlationId) {
    Principal principal = authenticate(request);
    if (principal == null) {
      return new Frame(null, correlationId, null, null, Set.of(), Set.of(), false);
    }
    request.setAttribute(PRINCIPAL, principal);
    Actor actor = Actor.user(principal.userUid(), principal.name(), principal.email());
    // A person's own account (password, second factor, passkeys, sessions, profile) belongs to no
    // pharmacy: its routes ignore the pharmacy the app names, so what happens to the account is
    // never written into a pharmacy's audit. Only the activity report needs the pharmacy (its
    // inactivity limit).
    boolean accountRoute = accountRoute(request);
    UUID tenantUid =
        accountRoute
            ? null
            : Uids.parse(streamFallback(request, TENANT_HEADER, "tenant"), "tenantId");
    UUID branchUid = accountRoute ? null : Uids.parse(request.getHeader(BRANCH_HEADER), "branchId");
    if (tenantUid == null) {
      if (branchUid != null) {
        throw Refusal.forbidden(
            "tenant_required", "Name the tenant of the branch with X-Tenant-Id.");
      }
      return new Frame(actor, correlationId, null, null, Set.of(), Set.of(), principal.rootAdmin());
    }
    TenantView tenant =
        tenants
            .find(tenantUid)
            .orElseThrow(() -> Refusal.forbidden("forbidden", "You may not act in this tenant."));

    Set<String> permissions = Set.of();
    Set<UUID> allowedBranches = null;
    if (!principal.rootAdmin()) {
      MembershipGrant grant =
          TenantContext.callAs(
                  tenantUid,
                  () ->
                      TenantContext.callAsUser(
                          principal.userUid(),
                          () -> memberships.resolve(principal.userUid(), tenantUid)))
              .filter(MembershipGrant::active)
              .orElseThrow(() -> Refusal.forbidden("forbidden", "You may not act in this tenant."));
      if (tenant.status() != TenantStatus.ACTIVE) {
        suspended(tenant, grant, request);
      }
      permissions = Set.copyOf(grant.permissions());
      allowedBranches = grant.allBranches() ? null : new HashSet<>(grant.branchUids());
      visits.working(principal.sessionUid(), principal.userUid(), tenantUid);
    }

    if (branchUid != null) {
      BranchView branch =
          TenantContext.callAs(tenantUid, () -> branches.find(branchUid))
              .filter(BranchView::active)
              .orElseThrow(
                  () -> Refusal.forbidden("branch_not_allowed", "You may not use this branch."));
      if (allowedBranches != null && !allowedBranches.contains(branch.uid())) {
        throw Refusal.forbidden("branch_not_allowed", "You may not use this branch.");
      }
    }
    return new Frame(
        actor,
        correlationId,
        tenantUid,
        branchUid,
        permissions,
        allowedBranches == null ? null : Set.copyOf(allowedBranches),
        principal.rootAdmin());
  }

  /** A route of the person's own account, outside any pharmacy (all of /auth but the activity). */
  static boolean accountRoute(HttpServletRequest request) {
    String uri = request.getRequestURI();
    return uri.startsWith(AUTH_PREFIX) && !uri.equals(AUTH_PREFIX + "activity");
  }

  /**
   * The notification stream is opened with the browser's EventSource, which cannot send headers: on
   * that one path only, {@code ?token=} and {@code ?tenant=} stand in for them.
   */
  private static String streamFallback(
      HttpServletRequest request, String headerName, String parameterName) {
    String header = request.getHeader(headerName);
    if ((header == null || header.isBlank()) && STREAM_PATH.equals(request.getRequestURI())) {
      return request.getParameter(parameterName);
    }
    return header;
  }

  private Principal authenticate(HttpServletRequest request) {
    String header = request.getHeader("Authorization");
    if ((header == null || header.isBlank()) && STREAM_PATH.equals(request.getRequestURI())) {
      String token = request.getParameter("token");
      header = token == null || token.isBlank() ? null : "Bearer " + token;
    }
    if (header == null || header.isBlank()) {
      return null;
    }
    if (!header.startsWith("Bearer ") || header.length() <= 7) {
      throw Refusal.unauthenticated("unauthenticated", "Send Authorization: Bearer <token>.");
    }
    return authentication
        .authenticate(header.substring(7).trim())
        .orElseThrow(
            () ->
                Refusal.unauthenticated(
                    "session_expired", "Your session has expired; sign in again."));
  }

  /**
   * The public sign-in steps (sign-in, its second factor, passkey sign-in, password reset,
   * accepting an invitation) are limited per client IP: 429 with Retry-After beyond the limit.
   */
  private void throttleSignIn(HttpServletRequest request) {
    if (!"POST".equals(request.getMethod()) || !request.getRequestURI().startsWith(AUTH_PREFIX)) {
      return;
    }
    String path = request.getRequestURI().substring(AUTH_PREFIX.length());
    if (path.equals("sign-out")
        || path.startsWith("2fa/")
        || path.startsWith("change-password")
        // Signed-in changes to one's own account, limited on their own (codes an hour).
        || path.equals("email-change")
        || path.equals("phone")
        || path.startsWith("phone/")) {
      return;
    }
    rateLimiter
        .acquire(
            "auth:" + ClientAddress.of(request), authRequestsPerMinute, AUTH_WINDOW, Instant.now())
        .ifPresent(
            seconds -> {
              throw Refusal.tooManyRequests(
                  "Too many sign-in attempts from this device; wait a moment.", seconds);
            });
  }

  /**
   * A suspended pharmacy. Unpaid: its admins may still read (GET), to pay and take their data out
   * (data portability); every change and everyone else is refused. Any other kind (a dispute's
   * legal hold, abuse): nobody works in it.
   */
  private static void suspended(
      TenantView tenant, MembershipGrant grant, HttpServletRequest request) {
    boolean admin =
        grant.permissions().contains("settings.manage")
            || grant.permissions().contains("users.manage");
    boolean reading = "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod());
    if (tenant.status() == TenantStatus.ERASED) {
      throw Refusal.forbidden("tenant_erased", "This pharmacy was closed and its data erased.");
    }
    if (tenant.status() == TenantStatus.CLOSED) {
      // Closed: until it is erased its admins may still read and export (data portability).
      if (admin && reading) {
        return;
      }
      throw Refusal.forbidden("tenant_closed", "This pharmacy is closed.");
    }
    if (tenant.suspensionKind()
            == tz.co.pharmacy.modules.tenancy.ports.inbound.SuspensionKind.UNPAID
        && admin
        && reading) {
      return;
    }
    if (tenant.suspensionKind()
        == tz.co.pharmacy.modules.tenancy.ports.inbound.SuspensionKind.UNPAID) {
      throw Refusal.forbidden(
          admin ? "tenant_suspended_read_only" : "tenant_suspended",
          admin
              ? "This pharmacy's subscription is unpaid: you can still see and export its data, but"
                  + " not change it, until it is paid."
              : "This pharmacy's subscription is unpaid; its admin can renew it.");
    }
    throw Refusal.forbidden("tenant_suspended", "This pharmacy's account is suspended.");
  }

  /** The client of a request (behind our proxies too), for sign-in records and device notices. */
  public static ClientInfo client(HttpServletRequest request) {
    String agent = request.getHeader("User-Agent");
    return new ClientInfo(
        ClientAddress.of(request), agent == null || agent.isBlank() ? null : agent);
  }

  private static String correlationId(HttpServletRequest request) {
    String given = request.getHeader(CORRELATION_HEADER);
    if (given != null && given.matches("[A-Za-z0-9._-]{8,64}")) {
      return given;
    }
    return UUID.randomUUID().toString();
  }
}
