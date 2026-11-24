package tz.co.pharmacy.delivery.edge;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.platform.kernel.Versioned;

/**
 * Sends the entity tag of an edited resource (RFC 9110 §8.8.3): a GET of one product, customer,
 * supplier, role, branch, price list or the settings, and the answer to a change of one, carry
 * {@code ETag: "<version>"}, the version a following change names in {@code If-Match} ({@link
 * Preconditions}). Lists carry each item's {@code version} in the body instead.
 */
@RestControllerAdvice
public class EntityTags implements ResponseBodyAdvice<Object> {

  @Override
  public boolean supports(
      MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
    return ApiResponse.class.isAssignableFrom(returnType.getParameterType());
  }

  @Override
  public Object beforeBodyWrite(
      Object body,
      MethodParameter returnType,
      MediaType selectedContentType,
      Class<? extends HttpMessageConverter<?>> selectedConverterType,
      ServerHttpRequest request,
      ServerHttpResponse response) {
    if (body instanceof ApiResponse<?> answer
        && answer.data() instanceof Versioned versioned
        && (HttpMethod.GET.equals(request.getMethod())
            || HttpMethod.PUT.equals(request.getMethod())
            || HttpMethod.PATCH.equals(request.getMethod()))) {
      response.getHeaders().setETag(Preconditions.tag(versioned.version()));
    }
    return body;
  }
}
