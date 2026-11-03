package tz.co.pharmacy.platform.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Async event delivery (listeners run after commit on their own, virtual, threads) and the clock.
 * The application runs in UTC; clients show times in the tenant's timezone.
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
@EnableScheduling
public class PlatformConfiguration {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
