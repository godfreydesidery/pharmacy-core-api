package tz.co.pharmacy.platform.composition;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tz.co.pharmacy.modules.fx.ports.outbound.RateProvider;

/**
 * The exchange-rate provider, chosen with {@code pharmacy.integrations.fx.provider}: {@code none}
 * (the default: rates are entered by hand under Finance → Exchange rates) or {@code
 * openexchangerates}, any exchangerate.host-style API answering {@code GET
 * <base-url>/latest?base=TZS&symbols=USD,KES} with {@code {"rates": {"USD": 0.000377, …}}}.
 */
@Configuration(proxyBeanMethods = false)
class RateProviders {

  @Bean
  @ConditionalOnProperty(
      name = "pharmacy.integrations.fx.provider",
      havingValue = "none",
      matchIfMissing = true)
  RateProvider noRateProvider() {
    return new RateProvider() {
      @Override
      public String name() {
        return "NONE";
      }

      @Override
      public Map<String, BigDecimal> latest(String base, Set<String> quotes) {
        return Map.of();
      }
    };
  }

  @Bean
  @ConditionalOnProperty(
      name = "pharmacy.integrations.fx.provider",
      havingValue = "openexchangerates")
  RateProvider openExchangeRatesProvider(
      @Value("${pharmacy.integrations.fx.base-url:https://api.exchangerate.host}") String baseUrl,
      @Value("${pharmacy.integrations.fx.api-key:}") String apiKey) {
    return new HttpRateProvider(baseUrl, apiKey);
  }

  /** An exchangerate.host-style HTTP API; failures are logged and yield no rates. */
  static final class HttpRateProvider implements RateProvider {

    private static final Logger LOG = LoggerFactory.getLogger(HttpRateProvider.class);
    private final HttpClient client =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final JsonMapper json = JsonMapper.builder().build();
    private final String baseUrl;
    private final String apiKey;

    HttpRateProvider(String baseUrl, String apiKey) {
      this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
      this.apiKey = apiKey;
    }

    @Override
    public String name() {
      return "OPENEXCHANGERATES";
    }

    @Override
    public Map<String, BigDecimal> latest(String base, Set<String> quotes) {
      String url =
          baseUrl
              + "/latest?base="
              + base
              + "&symbols="
              + String.join(",", quotes)
              + (apiKey.isBlank()
                  ? ""
                  : "&access_key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8));
      try {
        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
          LOG.warn("Rate provider answered {} for base {}", response.statusCode(), base);
          return Map.of();
        }
        JsonNode rates = json.readTree(response.body()).path("rates");
        Map<String, BigDecimal> latest = new LinkedHashMap<>();
        for (String quote : quotes) {
          JsonNode rate = rates.path(quote);
          if (rate.isNumber()) {
            latest.put(quote, rate.decimalValue());
          }
        }
        return latest;
      } catch (IOException | RuntimeException failure) {
        LOG.warn("Rate provider failed for base {}: {}", base, failure.getMessage());
        return Map.of();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return Map.of();
      }
    }
  }
}
