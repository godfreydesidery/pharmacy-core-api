package tz.co.pharmacy.platform.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Refuses to start a server that would run on a development default (OWASP ASVS V13.3,
 * Twelve-Factor III): a published encryption key or file-signing secret, reset and invitation links
 * that point to localhost, or uploaded files kept inside the container. It runs before any bean is
 * created, so a misconfigured deployment stops before it migrates or serves anything, and the
 * platform keeps the old deployment (readiness never turns green).
 *
 * <p>The {@code local} and {@code test} profiles (a laptop, the embedded database, the test suite)
 * are exempt: their defaults are there so nothing needs configuring.
 */
final class ProductionSafety implements BeanFactoryPostProcessor, EnvironmentAware {

  static final int MIN_SECRET_LENGTH = 32;
  static final String LOCAL_ENCRYPTION_KEY = "local-development-only-encryption-key";
  static final String LOCAL_FILE_SECRET = "local-development-file-signing-secret";

  private Environment environment;

  @Override
  public void setEnvironment(Environment environment) {
    this.environment = environment;
  }

  @Override
  public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
    require(environment);
  }

  /** Throws when the environment is not a development one and any rule is broken. */
  static void require(Environment environment) {
    if (environment.acceptsProfiles(Profiles.of("local", "test"))) {
      return;
    }
    List<String> problems = problems(environment);
    if (!problems.isEmpty()) {
      throw new IllegalStateException(
          "Refusing to start with development defaults outside the local and test profiles:\n - "
              + String.join("\n - ", problems));
    }
  }

  static List<String> problems(Environment environment) {
    List<String> problems = new ArrayList<>();
    secret(
        environment,
        "pharmacy.access.encryption-key",
        "PHARMACY_ACCESS_ENCRYPTIONKEY",
        LOCAL_ENCRYPTION_KEY,
        problems);
    // Uploads come through the API's signed links with either store.
    secret(
        environment,
        "pharmacy.files.signing-secret",
        "PHARMACY_FILES_SIGNING_SECRET",
        LOCAL_FILE_SECRET,
        problems);
    String storage = environment.getProperty("pharmacy.files.storage", "local");
    if ("s3".equalsIgnoreCase(storage.trim())
        && environment.getProperty("pharmacy.files.s3.bucket", "").isBlank()) {
      problems.add(
          "pharmacy.files.s3.bucket (PHARMACY_FILES_S3_BUCKET) is not set, with"
              + " PHARMACY_FILES_STORAGE=s3.");
    }
    if ("local".equalsIgnoreCase(storage.trim())) {
      String root = environment.getProperty("pharmacy.files.local.root", "target/files");
      // A container path (/data/files) is absolute on the Linux server even when checked on
      // Windows.
      if (!root.trim().startsWith("/") && !Path.of(root.trim()).isAbsolute()) {
        problems.add(
            "pharmacy.files.local.root (PHARMACY_FILES_ROOT) is the relative path '"
                + root
                + "', inside the container: set an absolute path on a volume, or use"
                + " PHARMACY_FILES_STORAGE=s3.");
      }
    }
    String webUrl = environment.getProperty("pharmacy.access.web-url", "");
    String host = webUrl.toLowerCase(Locale.ROOT);
    if (host.isBlank() || host.contains("://localhost") || host.contains("://127.0.0.1")) {
      problems.add(
          "pharmacy.access.web-url (PHARMACY_ACCESS_WEBURL) is not set to the web app's public"
              + " address, so password-reset and invitation links would point to this machine.");
    }
    return problems;
  }

  private static void secret(
      Environment environment,
      String property,
      String variable,
      String developmentValue,
      List<String> problems) {
    String value = environment.getProperty(property, "");
    if (value.isBlank() || value.equals(developmentValue)) {
      problems.add(property + " (" + variable + ") is not set; it is the published default.");
    } else if (value.length() < MIN_SECRET_LENGTH) {
      problems.add(
          property
              + " ("
              + variable
              + ") is shorter than "
              + MIN_SECRET_LENGTH
              + " characters; use a random value of at least that length.");
    }
  }
}
