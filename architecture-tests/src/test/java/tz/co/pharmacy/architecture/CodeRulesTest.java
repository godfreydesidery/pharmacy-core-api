package tz.co.pharmacy.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;

/** Code rules Spring Modulith does not check on its own. */
class CodeRulesTest {

  static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("tz.co.pharmacy");

  @Test
  @DisplayName("the platform depends on no module, application service, delivery or composition")
  void platformIsIndependent() {
    noClasses()
        .that()
        .resideInAPackage("tz.co.pharmacy.platform.(kernel|persistence|bus|config|crypto)..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "tz.co.pharmacy.modules..",
            "tz.co.pharmacy.application..",
            "tz.co.pharmacy.delivery..",
            "tz.co.pharmacy.platform.composition..")
        .check(CLASSES);
  }

  @Test
  @DisplayName("no module depends on delivery, composition or an application service")
  void modulesDoNotReachUpwards() {
    noClasses()
        .that()
        .resideInAPackage("tz.co.pharmacy.modules..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "tz.co.pharmacy.delivery..",
            "tz.co.pharmacy.platform.composition..",
            "tz.co.pharmacy.application..")
        .check(CLASSES);
  }

  @Test
  @DisplayName("entities and repositories stay inside their module")
  void internalsStayInternal() {
    noClasses()
        .that()
        .resideInAnyPackage("tz.co.pharmacy.delivery..", "tz.co.pharmacy.application..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "tz.co.pharmacy.modules.*.domain..",
            "tz.co.pharmacy.modules.*.persistence..",
            "tz.co.pharmacy.modules.*.application..")
        .check(CLASSES);
  }

  @Test
  @DisplayName("every handler method declares @Access (the edge refuses one that does not)")
  void everyHandlerDeclaresAccess() {
    methods()
        .that()
        .areDeclaredInClassesThat()
        .areAnnotatedWith(RestController.class)
        .and()
        .arePublic()
        .and()
        .areDeclaredInClassesThat()
        .resideInAPackage("tz.co.pharmacy.delivery..")
        .should()
        .beAnnotatedWith(Access.class)
        .check(CLASSES);
  }

  @Test
  @DisplayName("time comes from the application clock, so a simulated world can move it")
  void timeComesFromTheClock() {
    noClasses()
        .that()
        .doNotHaveFullyQualifiedName("tz.co.pharmacy.platform.kernel.Clocks")
        .and()
        .doNotHaveFullyQualifiedName("tz.co.pharmacy.platform.kernel.SimulatedClock")
        .and()
        .doNotHaveFullyQualifiedName("tz.co.pharmacy.platform.config.PlatformConfiguration")
        // Wall time on purpose: uid ordering, TOTP codes from the user's phone, and request
        // throttles, which measure real traffic.
        .and()
        .doNotHaveFullyQualifiedName("tz.co.pharmacy.platform.kernel.Uids")
        .and()
        .doNotHaveFullyQualifiedName(
            "tz.co.pharmacy.modules.access.application.TwoFactorServiceImpl")
        .and()
        .resideOutsideOfPackage("tz.co.pharmacy.delivery.edge..")
        .should()
        .callMethod(java.time.Instant.class, "now")
        .orShould()
        .callMethod(java.time.LocalDate.class, "now")
        .orShould()
        .callMethod(java.time.LocalDateTime.class, "now")
        .orShould()
        .callMethod(java.time.OffsetDateTime.class, "now")
        .orShould()
        .callMethod(java.time.ZonedDateTime.class, "now")
        .orShould()
        .callMethod(java.time.LocalTime.class, "now")
        .orShould()
        .callMethod(System.class, "currentTimeMillis")
        .orShould()
        .callMethod(java.time.Clock.class, "systemUTC")
        .orShould()
        .callMethod(java.time.Clock.class, "systemDefaultZone")
        .because("Instant.now() and friends ignore the simulated clock")
        .check(CLASSES);
  }

  /**
   * Spring Modulith keeps every published event in {@code platform.event_publication}, one table
   * for every pharmacy, with no row-level security (see platform V9): an event must carry nothing
   * that lets someone in or spends money, such as a token, a password, a code or a key.
   */
  @Test
  @DisplayName("domain events carry no secrets: no token, password, code, key or signature")
  void eventsCarryNoSecrets() {
    java.util.regex.Pattern secret =
        java.util.regex.Pattern.compile(
            "(?i).*(token|password|secret|otp|cvv|apikey|signature|hash|seed).*");
    java.util.List<String> found = new java.util.ArrayList<>();
    for (com.tngtech.archunit.core.domain.JavaClass type : CLASSES) {
      if (!type.getPackageName().matches("tz\\.co\\.pharmacy\\.modules\\.[a-z]+\\.events")) {
        continue;
      }
      Class<?> reflected = type.reflect();
      if (!reflected.isRecord()) {
        continue;
      }
      for (java.lang.reflect.RecordComponent component : reflected.getRecordComponents()) {
        if (secret.matcher(component.getName()).matches()) {
          found.add(reflected.getName() + "." + component.getName());
        }
      }
    }
    org.assertj.core.api.Assertions.assertThat(found).isEmpty();
  }

  @Test
  @DisplayName("handlers live in delivery.api.v1")
  void handlersAreVersioned() {
    classes()
        .that()
        .areAnnotatedWith(RestController.class)
        .should()
        .resideInAPackage("tz.co.pharmacy.delivery.api.v1..")
        .check(CLASSES);
  }

  @Test
  @DisplayName(
      "the local stack runs the PostgreSQL major the tests and production run (18), backups alike")
  void localStackRunsProductionPostgres() throws java.io.IOException {
    java.nio.file.Path root = java.nio.file.Path.of("..").toAbsolutePath().normalize();
    String pom = java.nio.file.Files.readString(root.resolve("pom.xml"));
    java.util.regex.Matcher binaries =
        java.util.regex.Pattern.compile("<embedded-postgres-binaries.version>(\\d+)\\.")
            .matcher(pom);
    org.assertj.core.api.Assertions.assertThat(binaries.find()).isTrue();
    String major = binaries.group(1);
    org.assertj.core.api.Assertions.assertThat(major).isEqualTo("18");
    String compose = java.nio.file.Files.readString(root.resolve("docker-compose.yml"));
    org.assertj.core.api.Assertions.assertThat(compose)
        .containsPattern("(?m)^\\s*image: postgres:" + major + "\\s*$")
        // Version 18 images keep their data under /var/lib/postgresql/<major>.
        .containsPattern("(?m):/var/lib/postgresql\\s*$");
    org.assertj.core.api.Assertions.assertThat(
            java.nio.file.Files.readString(root.resolve("docs/runbook/backup-restore.md")))
        .contains("`postgres:" + major + "-alpine`");
  }
}
