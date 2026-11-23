package tz.co.pharmacy.delivery.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import jakarta.servlet.DispatcherType;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.modules.tenancy.ports.inbound.Entitlements;
import tz.co.pharmacy.platform.kernel.Actor;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Refusal;

class AccessInterceptorTest {

  /** A handler of this application that needs a signed-in caller, like the notification stream. */
  static class Streams {
    @Access(scope = AccessScope.AUTHENTICATED)
    public void stream() {}
  }

  /** Reads that need one of two permissions, or two at once (ISO-10). */
  static class Reads {
    @Access(
        scope = AccessScope.TENANT,
        anyOf = {"sales.sell", "finance.view"})
    public void account() {}

    @Access(
        scope = AccessScope.TENANT,
        allOf = {"partners.view", "data.export"})
    public void export() {}
  }

  private final AccessInterceptor interceptor =
      new AccessInterceptor(mock(Entitlements.class), mock(TenantPolicies.class));

  private HandlerMethod handler() throws NoSuchMethodException {
    return new HandlerMethod(new Streams(), Streams.class.getMethod("stream"));
  }

  @Test
  @DisplayName("a request without a caller is refused")
  void refusesAnonymous() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/notifications");
    HandlerMethod handler = handler();
    assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), handler))
        .isInstanceOf(Refusal.class)
        .hasMessageContaining("Sign in");
  }

  private boolean callAs(String method, String... permissions) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/reads");
    HandlerMethod handler = new HandlerMethod(new Reads(), Reads.class.getMethod(method));
    ExecutionContext.enter(
        new ExecutionContext.Frame(
            Actor.user(UUID.randomUUID(), "Staff", "staff@test.local"),
            "test",
            UUID.randomUUID(),
            null,
            Set.of(permissions),
            null,
            false));
    try {
      return interceptor.preHandle(request, new MockHttpServletResponse(), handler);
    } finally {
      ExecutionContext.leave();
    }
  }

  @Test
  @DisplayName("anyOf: one of the permissions is enough; none is refused")
  void anyOfOnePermission() throws Exception {
    assertThat(callAs("account", "sales.sell")).isTrue();
    assertThat(callAs("account", "finance.view")).isTrue();
    assertThatThrownBy(() -> callAs("account", "inventory.view"))
        .isInstanceOf(Refusal.class)
        .hasMessageContaining("sales.sell, finance.view");
  }

  @Test
  @DisplayName("allOf: every permission is needed")
  void allOfEveryPermission() throws Exception {
    assertThat(callAs("export", "partners.view", "data.export")).isTrue();
    assertThatThrownBy(() -> callAs("export", "partners.view"))
        .isInstanceOf(Refusal.class)
        .hasMessageContaining("data.export");
    assertThatThrownBy(() -> callAs("export", "data.export"))
        .isInstanceOf(Refusal.class)
        .hasMessageContaining("partners.view");
  }

  @Test
  @DisplayName("the async dispatch that ends a stream is not checked again")
  void letsAsyncDispatchThrough() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/notifications");
    request.setDispatcherType(DispatcherType.ASYNC);
    assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), handler())).isTrue();
  }
}
