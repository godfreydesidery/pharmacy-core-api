package tz.co.pharmacy.delivery.edge;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class WebConfiguration implements WebMvcConfigurer {

  private final AccessInterceptor accessInterceptor;
  private final IdempotencyInterceptor idempotencyInterceptor;
  private final String[] allowedOrigins;

  public WebConfiguration(
      AccessInterceptor accessInterceptor,
      IdempotencyInterceptor idempotencyInterceptor,
      @Value("${pharmacy.edge.cors-allowed-origins:}") String[] allowedOrigins) {
    this.accessInterceptor = accessInterceptor;
    this.idempotencyInterceptor = idempotencyInterceptor;
    this.allowedOrigins = allowedOrigins;
  }

  /** Access first: only a caller allowed to use a route reserves or replays an idempotency key. */
  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(accessInterceptor).addPathPatterns("/api/**");
    registry.addInterceptor(idempotencyInterceptor).addPathPatterns("/api/**");
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    if (allowedOrigins.length > 0) {
      registry
          .addMapping("/api/**")
          .allowedOrigins(allowedOrigins)
          .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
          .allowedHeaders("*")
          // Retry-After: the web waits as long as a 429 or 503 says before it sends again.
          .exposedHeaders(
              EdgeFilter.CORRELATION_HEADER, IdempotencyFilter.REPLAYED_HEADER, "Retry-After")
          .maxAge(3600);
    }
  }
}
