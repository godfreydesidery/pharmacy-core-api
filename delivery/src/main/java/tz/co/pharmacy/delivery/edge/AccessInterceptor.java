package tz.co.pharmacy.delivery.edge;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.CountsAgainst;
import tz.co.pharmacy.delivery.api.RequiresFeature;
import tz.co.pharmacy.modules.tenancy.ports.inbound.Entitlements;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.ExecutionContext.Frame;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * Enforces each handler's {@link Access}: a handler method of this application without one is
 * refused (fail closed), whatever it does. The scope decides what the request must carry; the
 * permission must be one the membership's role grants, or the caller a root administrator.
 */
@Component
public class AccessInterceptor implements HandlerInterceptor {

  private static final String OWN_PACKAGE = "tz.co.pharmacy.";

  private final Entitlements entitlements;

  private final TenantPolicies policies;

  private final java.time.Duration stepUp;

  public AccessInterceptor(Entitlements entitlements, TenantPolicies policies) {
    this(entitlements, policies, 10);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public AccessInterceptor(
      Entitlements entitlements,
      TenantPolicies policies,
      @org.springframework.beans.factory.annotation.Value("${pharmacy.access.step-up-minutes:10}")
          long stepUpMinutes) {
    this.entitlements = entitlements;
    this.policies = policies;
    this.stepUp = java.time.Duration.ofMinutes(stepUpMinutes);
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!(handler instanceof HandlerMethod method)
        || !method.getBeanType().getName().startsWith(OWN_PACKAGE)) {
      return true;
    }
    if (request.getDispatcherType() == DispatcherType.ASYNC) {
      // The end of an async request (a notification stream closing): it was checked when it
      // started, and the edge filter does not run again to rebuild the caller.
      return true;
    }
    Access access = method.getMethodAnnotation(Access.class);
    if (access == null) {
      throw Refusal.forbidden("forbidden", "This route is not open to anyone.");
    }
    if (access.scope() == AccessScope.PUBLIC) {
      return true;
    }
    Frame frame =
        ExecutionContext.current()
            .filter(current -> current.actor() != null)
            .orElseThrow(
                () -> Refusal.unauthenticated("unauthenticated", "Sign in to use this route."));
    if (access.scope() != AccessScope.AUTHENTICATED
        && request.getAttribute(EdgeFilter.PRINCIPAL)
            instanceof tz.co.pharmacy.modules.access.ports.inbound.Principal principal) {
      if (principal.passwordChangeRequired()) {
        throw Refusal.forbidden(
            "password_change_required",
            "Choose your own password before you start: yours was set by your pharmacy.");
      }
      if (principal.twoFactorSetupRequired()) {
        throw Refusal.forbidden(
            "two_factor_setup_required",
            "Set up two-factor sign-in on your account before using the platform.");
      }
      // G16: a pharmacy that requires a second factor of its staff. Root administrators answer to
      // the platform's own rule above.
      if (!principal.rootAdmin()
          && !principal.secondFactor()
          && frame.tenantUid() != null
          && policies.requiresTwoFactor(frame.tenantUid())) {
        throw Refusal.forbidden(
            "two_factor_required_by_pharmacy",
            "This pharmacy requires two-factor sign-in: set up an authenticator app or a passkey"
                + " on your account first.");
      }
    }
    switch (access.scope()) {
      case PLATFORM -> {
        if (!frame.rootAdmin()) {
          throw Refusal.forbidden("forbidden", "Only a root administrator may use this route.");
        }
        if (frame.tenantUid() != null) {
          // A platform route acts for the platform, never inside a pharmacy the request happens
          // to name: what it writes (a reset message, audit) must not land in that pharmacy
          // (adversarial review ISO-4). The edge filter clears both contexts when it ends.
          ExecutionContext.enter(
              new Frame(
                  frame.actor(),
                  frame.correlationId(),
                  null,
                  null,
                  frame.permissions(),
                  frame.allowedBranches(),
                  frame.rootAdmin()));
          tz.co.pharmacy.platform.persistence.TenantContext.enter(null, frame.actor().uid());
        }
      }
      case TENANT, TENANT_OPTIONAL_BRANCH -> requireTenant(frame);
      case BRANCH -> {
        requireTenant(frame);
        if (frame.branchUid() == null) {
          throw Refusal.forbidden("branch_required", "Name the branch with X-Branch-Id.");
        }
      }
      default -> {}
    }
    if (access.stepUp()) {
      Object principal = request.getAttribute(EdgeFilter.PRINCIPAL);
      boolean recent =
          principal instanceof tz.co.pharmacy.modules.access.ports.inbound.Principal signedIn
              && signedIn.verifiedSince(tz.co.pharmacy.platform.kernel.Clocks.now().minus(stepUp));
      if (!recent) {
        throw Refusal.forbidden(
            "reauthentication_required",
            "Confirm your password to make this change to your account.");
      }
    }
    for (String required : access.allOf()) {
      if (!frame.permits(required)) {
        throw Refusal.forbidden(
            "forbidden", "Your role does not include the permission " + required + ".");
      }
    }
    if (access.anyOf().length > 0
        && java.util.Arrays.stream(access.anyOf()).noneMatch(frame::permits)) {
      throw Refusal.forbidden(
          "forbidden",
          "Your role does not include any of the permissions "
              + String.join(", ", access.anyOf())
              + ".");
    }
    if (!access.permission().isEmpty() && !frame.permits(access.permission())) {
      throw Refusal.forbidden(
          "forbidden", "Your role does not include the permission " + access.permission() + ".");
    }
    checkPlan(method, frame);
    return true;
  }

  /**
   * The tenant's subscription plan: the module a handler needs, and room under the cap of what it
   * adds. A root administrator is never held back by a plan.
   */
  private void checkPlan(HandlerMethod method, Frame frame) {
    if (frame.rootAdmin() || frame.tenantUid() == null) {
      return;
    }
    RequiresFeature feature = method.getMethodAnnotation(RequiresFeature.class);
    if (feature == null) {
      feature = method.getBeanType().getAnnotation(RequiresFeature.class);
    }
    if (feature != null) {
      entitlements.requireFeature(frame.tenantUid(), feature.value());
    }
    CountsAgainst limit = method.getMethodAnnotation(CountsAgainst.class);
    if (limit != null) {
      entitlements.requireRoom(frame.tenantUid(), limit.value());
    }
  }

  private static void requireTenant(Frame frame) {
    if (frame.tenantUid() == null) {
      throw Refusal.forbidden("tenant_required", "Name the tenant with X-Tenant-Id.");
    }
  }
}
