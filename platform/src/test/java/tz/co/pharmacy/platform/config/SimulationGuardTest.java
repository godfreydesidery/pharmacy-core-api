package tz.co.pharmacy.platform.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SimulationGuardTest {

  static MockEnvironment profiles(String... active) {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles(active);
    return environment;
  }

  @Test
  @DisplayName("sim on a server (no local profile, no opt-in) refuses to start")
  void refusesOnAServer() {
    assertThatThrownBy(() -> PlatformConfiguration.requireSafeSimulation(profiles("sim")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PHARMACY_SIM_ALLOWED");
    assertThatThrownBy(() -> PlatformConfiguration.requireSafeSimulation(profiles("prod", "sim")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("sim beside the local profile starts: the world on one machine")
  void startsLocally() {
    assertThatCode(() -> PlatformConfiguration.requireSafeSimulation(profiles("local", "sim")))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("sim on a dedicated demo service starts only with the explicit opt-in")
  void startsOnADemoServiceWhenAllowed() {
    MockEnvironment demo = profiles("sim").withProperty("pharmacy.sim.allowed", "true");
    assertThatCode(() -> PlatformConfiguration.requireSafeSimulation(demo))
        .doesNotThrowAnyException();

    MockEnvironment notAllowed = profiles("sim").withProperty("pharmacy.sim.allowed", "false");
    assertThatThrownBy(() -> PlatformConfiguration.requireSafeSimulation(notAllowed))
        .isInstanceOf(IllegalStateException.class);
  }
}
