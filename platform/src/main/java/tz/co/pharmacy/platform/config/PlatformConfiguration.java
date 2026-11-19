package tz.co.pharmacy.platform.config;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.StringUtils;
import tz.co.pharmacy.platform.kernel.Clocks;
import tz.co.pharmacy.platform.kernel.SimulatedClock;

/**
 * Async event delivery (listeners run after commit on their own, virtual, threads) and the clock.
 * The application runs in UTC; clients show times in the tenant's timezone.
 *
 * <p>The clock is the system clock, except under the {@code sim} profile, where it is a {@link
 * SimulatedClock} that a simulated world moves forward. Either way it is installed in {@link
 * Clocks}, so entities and injected services read the same time.
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
@EnableScheduling
public class PlatformConfiguration {

  @Bean
  @Profile("!sim")
  Clock clock() {
    return Clocks.install(Clock.systemUTC());
  }

  /**
   * {@code pharmacy.sim.start}: where a new world begins (an ISO instant), or now. {@code
   * pharmacy.sim.clock-file}: where the clock's position survives a restart.
   */
  @Bean
  @Profile("sim")
  SimulatedClock simulatedClock(
      @Value("${pharmacy.sim.start:}") String start,
      @Value("${pharmacy.sim.clock-file:target/sim-clock.txt}") String clockFile) {
    return Clocks.install(
        new SimulatedClock(
            Clock.systemUTC(),
            StringUtils.hasText(start) ? Instant.parse(start.trim()) : null,
            StringUtils.hasText(clockFile) ? Path.of(clockFile.trim()) : null));
  }
}
