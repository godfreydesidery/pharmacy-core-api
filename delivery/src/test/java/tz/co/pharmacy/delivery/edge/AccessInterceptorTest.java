package tz.co.pharmacy.delivery.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.modules.tenancy.ports.inbound.Entitlements;
import tz.co.pharmacy.platform.kernel.Refusal;

class AccessInterceptorTest {

  /** A handler of this application that needs a signed-in caller, like the notification stream. */
  static class Streams {
    @Access(scope = AccessScope.AUTHENTICATED)
    public void stream() {}
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

  @Test
  @DisplayName("the async dispatch that ends a stream is not checked again")
  void letsAsyncDispatchThrough() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/notifications");
    request.setDispatcherType(DispatcherType.ASYNC);
    assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), handler())).isTrue();
  }
}
