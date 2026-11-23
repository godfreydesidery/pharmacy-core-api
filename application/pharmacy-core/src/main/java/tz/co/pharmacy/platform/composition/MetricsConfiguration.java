package tz.co.pharmacy.platform.composition;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.boot.actuate.endpoint.EndpointFilter;
import org.springframework.boot.actuate.endpoint.EndpointId;
import org.springframework.boot.actuate.endpoint.web.ExposableWebEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Where the metrics are published. Prometheus scrapes {@code /actuator/prometheus} on the
 * management port ({@code PHARMACY_MANAGEMENT_PORT}, 8081 on ECS), which the load balancer does not
 * route and only the VPC reaches, the usual set-up for containers behind an ALB. Without a separate
 * management port actuator shares the public API port, so the endpoint is left out there, unless
 * {@code pharmacy.management.metrics-on-api-port} says that port is private (local, test).
 */
@Configuration(proxyBeanMethods = false)
class MetricsConfiguration {

  private static final EndpointId PROMETHEUS = EndpointId.of("prometheus");

  @Bean
  EndpointFilter<ExposableWebEndpoint> prometheusOnlyOnPrivatePort(
      Environment environment,
      @Value("${pharmacy.management.metrics-on-api-port:false}") boolean onApiPort) {
    boolean separatePort = ManagementPortType.get(environment) == ManagementPortType.DIFFERENT;
    return endpoint -> separatePort || onApiPort || !PROMETHEUS.equals(endpoint.getEndpointId());
  }
}
