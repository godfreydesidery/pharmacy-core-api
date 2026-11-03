package tz.co.pharmacy.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;
import tz.co.pharmacy.PharmacyApplication;

/**
 * Spring Modulith verifies that no module reaches another module's internals and that no cycle
 * exists. Modules expose only their {@code ports.inbound}, {@code ports.outbound} and {@code
 * events} named interfaces; the platform packages are open by design.
 */
class ModuleStructureTest {

  static final ApplicationModules MODULES = ApplicationModules.of(PharmacyApplication.class);

  @Test
  @DisplayName("modules meet only through their named interfaces, without cycles")
  void modulesVerify() {
    MODULES.verify();
  }

  @Test
  @DisplayName("the expected modules exist")
  void expectedModules() {
    assertThat(MODULES.stream().map(ApplicationModule::getDisplayName))
        .contains(
            "tenancy",
            "access",
            "catalogue",
            "partners",
            "inventory",
            "purchasing",
            "sales",
            "finance",
            "reporting",
            "integrations",
            "fx",
            "notifications",
            "files",
            "receivables",
            "insurance",
            "onboarding",
            "checkout",
            "receiving",
            "delivery",
            "composition",
            "platform-kernel",
            "platform-persistence",
            "platform-bus",
            "platform-config");
  }

  @Test
  @DisplayName(
      "writes the module documentation (PlantUML and canvases) to target/spring-modulith-docs")
  void documentModules() {
    new Documenter(MODULES).writeDocumentation();
  }
}
