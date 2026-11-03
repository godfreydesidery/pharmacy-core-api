package tz.co.pharmacy.delivery.edge;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class WebConfiguration implements WebMvcConfigurer {

  private final AccessInterceptor accessInterceptor;
  private final String[] allowedOrigins;

  public WebConfiguration(
      AccessInterceptor accessInterceptor,
      @Value("${pharmacy.edge.cors-allowed-origins:}") String[] allowedOrigins) {
    this.accessInterceptor = accessInterceptor;
    this.allowedOrigins = allowedOrigins;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(accessInterceptor).addPathPatterns("/api/**");
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    if (allowedOrigins.length > 0) {
      registry
          .addMapping("/api/**")
          .allowedOrigins(allowedOrigins)
          .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
          .allowedHeaders("*")
          .exposedHeaders(EdgeFilter.CORRELATION_HEADER)
          .maxAge(3600);
    }
  }
}
