package tz.co.pharmacy.platform.config;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.StringUtils;
import tz.co.pharmacy.platform.kernel.Clocks;
import tz.co.pharmacy.platform.kernel.SimulatedClock;

/**
 * Async event delivery (listeners run after commit on the bounded pool of {@link AsyncExecution})
 * and the clock. The application runs in UTC; clients show times in the tenant's timezone.
 *
 * <p>The clock is the system clock, except under the {@code sim} profile, where it is a {@link
 * SimulatedClock} that a simulated world moves forward. Either way it is installed in {@link
 * Clocks}, so entities and injected services read the same time.
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
@EnableScheduling
public class PlatformConfiguration {

  /** Stops a server that would run on development defaults, before anything else starts. */
  @Bean
  static ProductionSafety productionSafety() {
    return new ProductionSafety();
  }

  @Bean
  @Profile("!sim")
  Clock clock() {
    return Clocks.install(Clock.systemUTC());
  }

  /**
   * {@code pharmacy.sim.start}: where a new world begins (an ISO instant), or now. {@code
   * pharmacy.sim.clock-file}: where the clock's position survives a restart.
   */
  /**
   * The simulated clock moves every pharmacy's time forward for good, and the {@code sim} profile
   * also lengthens sessions, lifts the sign-in limit and lets the payment simulator decline. It is
   * for a world on one machine or a dedicated demo service, never a server with real pharmacies, so
   * it starts only beside the {@code local} profile (the embedded database) or where {@code
   * pharmacy.sim.allowed=true} (PHARMACY_SIM_ALLOWED) declares on purpose that this deployment is a
   * simulation.
   */
  static void requireSafeSimulation(Environment environment) {
    boolean local = environment.acceptsProfiles(Profiles.of("local"));
    boolean allowed = environment.getProperty("pharmacy.sim.allowed", Boolean.class, false);
    if (!local && !allowed) {
      throw new IllegalStateException(
          "The sim profile is on without the local profile or pharmacy.sim.allowed=true. It moves"
              + " every pharmacy's clock forward for good and relaxes sessions, sign-in limits and"
              + " payments, so it must never run where real pharmacies trade: remove sim from"
              + " SPRING_PROFILES_ACTIVE, or set PHARMACY_SIM_ALLOWED=true on a dedicated demo"
              + " service.");
    }
  }

  @Bean
  @Profile("sim")
  SimulatedClock simulatedClock(
      Environment environment,
      @Value("${pharmacy.sim.start:}") String start,
      @Value("${pharmacy.sim.clock-file:target/sim-clock.txt}") String clockFile) {
    requireSafeSimulation(environment);
    return Clocks.install(
        new SimulatedClock(
            Clock.systemUTC(),
            StringUtils.hasText(start) ? Instant.parse(start.trim()) : null,
            StringUtils.hasText(clockFile) ? Path.of(clockFile.trim()) : null));
  }
}
