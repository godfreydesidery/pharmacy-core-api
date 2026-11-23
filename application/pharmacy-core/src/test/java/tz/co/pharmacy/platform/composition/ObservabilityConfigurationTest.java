package tz.co.pharmacy.platform.composition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.sentry.Sentry;
import io.sentry.spring.boot4.SentryAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.EndpointFilter;
import org.springframework.boot.actuate.endpoint.EndpointId;
import org.springframework.boot.actuate.endpoint.web.ExposableWebEndpoint;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

/** Where metrics are published, the log format switch and the optional error tracker. */
class ObservabilityConfigurationTest {

  private static ExposableWebEndpoint endpoint(String id) {
    ExposableWebEndpoint endpoint = mock(ExposableWebEndpoint.class);
    when(endpoint.getEndpointId()).thenReturn(EndpointId.of(id));
    return endpoint;
  }

  @Test
  @DisplayName("Prometheus is published on a separate management port only (or where allowed)")
  void prometheusOnlyOnPrivatePort() {
    MetricsConfiguration configuration = new MetricsConfiguration();
    MockEnvironment shared = new MockEnvironment().withProperty("server.port", "8080");
    EndpointFilter<ExposableWebEndpoint> onApiPort =
        configuration.prometheusOnlyOnPrivatePort(shared, false);
    assertThat(onApiPort.match(endpoint("prometheus"))).isFalse();
    assertThat(onApiPort.match(endpoint("health"))).isTrue();

    MockEnvironment separate =
        new MockEnvironment()
            .withProperty("server.port", "8080")
            .withProperty("management.server.port", "8081");
    assertThat(
            configuration
                .prometheusOnlyOnPrivatePort(separate, false)
                .match(endpoint("prometheus")))
        .isTrue();
    assertThat(
            configuration.prometheusOnlyOnPrivatePort(shared, true).match(endpoint("prometheus")))
        .isTrue();
    // PHARMACY_MANAGEMENT_PORT unset: an empty port is the API's port.
    MockEnvironment unset =
        new MockEnvironment()
            .withProperty("server.port", "8080")
            .withProperty("management.server.port", "");
    assertThat(
            configuration.prometheusOnlyOnPrivatePort(unset, false).match(endpoint("prometheus")))
        .isFalse();
  }

  @Test
  @DisplayName("PHARMACY_LOG_FORMAT=json selects ECS JSON logs; unset or unknown is plain text")
  void logFormat() {
    String expression = "${pharmacy.log.formats.${PHARMACY_LOG_FORMAT:plain}:}";
    MockEnvironment json =
        new MockEnvironment()
            .withProperty("pharmacy.log.formats.json", "ecs")
            .withProperty("PHARMACY_LOG_FORMAT", "json");
    assertThat(json.resolvePlaceholders(expression)).isEqualTo("ecs");
    MockEnvironment unset = new MockEnvironment().withProperty("pharmacy.log.formats.json", "ecs");
    assertThat(unset.resolvePlaceholders(expression)).isEmpty();
    MockEnvironment unknown =
        new MockEnvironment()
            .withProperty("pharmacy.log.formats.json", "ecs")
            .withProperty("PHARMACY_LOG_FORMAT", "fancy");
    assertThat(unknown.resolvePlaceholders(expression)).isEmpty();
  }

  @Test
  @DisplayName("the error tracker stays off without SENTRY_DSN and starts, PII off, with one")
  void errorTracker() {
    ApplicationContextRunner runner =
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SentryAutoConfiguration.class))
            .withPropertyValues("sentry.send-default-pii=false");
    runner.run(context -> assertThat(context).doesNotHaveBean(io.sentry.IScopes.class));
    runner
        .withPropertyValues("sentry.dsn=https://public@o0.ingest.sentry.io/0")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(Sentry.isEnabled()).isTrue();
              assertThat(Sentry.getCurrentScopes().getOptions().isSendDefaultPii()).isFalse();
            });
    Sentry.close();
  }
}
