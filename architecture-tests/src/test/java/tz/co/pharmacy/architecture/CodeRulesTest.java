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
        .resideInAPackage("tz.co.pharmacy.platform.(kernel|persistence|bus|config)..")
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
  @DisplayName("handlers live in delivery.api.v1")
  void handlersAreVersioned() {
    classes()
        .that()
        .areAnnotatedWith(RestController.class)
        .should()
        .resideInAPackage("tz.co.pharmacy.delivery.api.v1..")
        .check(CLASSES);
  }
}
