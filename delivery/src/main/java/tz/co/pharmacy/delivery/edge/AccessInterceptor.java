package tz.co.pharmacy.delivery.edge;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
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

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!(handler instanceof HandlerMethod method)
        || !method.getBeanType().getName().startsWith(OWN_PACKAGE)) {
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
            instanceof tz.co.pharmacy.modules.access.ports.inbound.Principal principal
        && principal.twoFactorSetupRequired()) {
      throw Refusal.forbidden(
          "two_factor_setup_required",
          "Set up two-factor sign-in on your account before using the platform.");
    }
    switch (access.scope()) {
      case PLATFORM -> {
        if (!frame.rootAdmin()) {
          throw Refusal.forbidden("forbidden", "Only a root administrator may use this route.");
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
    if (!access.permission().isEmpty() && !frame.permits(access.permission())) {
      throw Refusal.forbidden(
          "forbidden", "Your role does not include the permission " + access.permission() + ".");
    }
    return true;
  }

  private static void requireTenant(Frame frame) {
    if (frame.tenantUid() == null) {
      throw Refusal.forbidden("tenant_required", "Name the tenant with X-Tenant-Id.");
    }
  }
}
