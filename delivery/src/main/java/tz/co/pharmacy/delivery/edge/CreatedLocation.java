package tz.co.pharmacy.delivery.edge;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import org.springframework.web.util.pattern.PathPattern;
import tz.co.pharmacy.delivery.api.ApiResponse;

/**
 * RFC 9110 section 15.3.2: a 201 names the resource it created in {@code Location} (STD-16). The
 * created resource is the data's {@code uid} under the route that created it ({@code POST
 * /api/v1/products} answers {@code Location: /api/v1/products/<uid>}), named only when the API has
 * a GET route for that path, so a Location can always be read. A 201 whose data has no uid (a
 * session, a token) or whose resource has no route of its own keeps no Location; a body is never
 * changed.
 */
@RestControllerAdvice
class CreatedLocation implements ResponseBodyAdvice<Object> {

  private final RequestMappingHandlerMapping mappings;
  private volatile List<PathPattern> reads;

  CreatedLocation(
      @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings) {
    this.mappings = mappings;
  }

  @Override
  public boolean supports(
      MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
    return true;
  }

  @Override
  public Object beforeBodyWrite(
      Object body,
      MethodParameter returnType,
      MediaType contentType,
      Class<? extends HttpMessageConverter<?>> converterType,
      ServerHttpRequest request,
      ServerHttpResponse response) {
    if (!(response instanceof ServletServerHttpResponse servlet)
        || servlet.getServletResponse().getStatus() != 201
        || response.getHeaders().getLocation() != null
        || servlet.getServletResponse().containsHeader("Location")
        || !(body instanceof ApiResponse<?> envelope)) {
      return body;
    }
    Object uid = uidOf(envelope.data());
    if (uid == null) {
      return body;
    }
    String path = request.getURI().getRawPath();
    String location = (path.endsWith("/") ? path : path + "/") + uid;
    if (readable(location)) {
      response.getHeaders().setLocation(URI.create(location));
    }
    return body;
  }

  /** The {@code uid} of a created resource's view (a record or bean with uid()), else null. */
  static Object uidOf(Object data) {
    if (data == null) {
      return null;
    }
    try {
      Method accessor = data.getClass().getMethod("uid");
      Object uid = accessor.invoke(data);
      return uid instanceof UUID || (uid instanceof String text && !text.isBlank()) ? uid : null;
    } catch (ReflectiveOperationException | RuntimeException none) {
      return null;
    }
  }

  /** Whether a GET route of the API matches {@code path}. */
  private boolean readable(String path) {
    PathContainer container = PathContainer.parsePath(path);
    return reads().stream().anyMatch(pattern -> pattern.matches(container));
  }

  private List<PathPattern> reads() {
    List<PathPattern> known = reads;
    if (known == null) {
      known =
          mappings.getHandlerMethods().keySet().stream()
              .filter(CreatedLocation::isGet)
              .filter(info -> info.getPathPatternsCondition() != null)
              .flatMap(info -> info.getPathPatternsCondition().getPatterns().stream())
              .toList();
      reads = known;
    }
    return known;
  }

  private static boolean isGet(RequestMappingInfo info) {
    var methods = info.getMethodsCondition().getMethods();
    return methods.isEmpty() || methods.contains(RequestMethod.GET);
  }
}
