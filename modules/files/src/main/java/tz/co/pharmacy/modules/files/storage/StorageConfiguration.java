package tz.co.pharmacy.modules.files.storage;

import java.nio.file.Path;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import tz.co.pharmacy.modules.files.ports.outbound.ObjectStorage;

/**
 * Chooses the object store by {@code pharmacy.files.storage}: {@code local} (the default; a
 * directory, {@code pharmacy.files.local.root}, for one instance) or {@code s3} ({@code
 * pharmacy.files.s3.*}, see {@link S3Settings}; any number of instances). Either way the API signs
 * its upload links with {@code pharmacy.files.signing-secret} ({@link BlobUrls}).
 */
@Configuration(proxyBeanMethods = false)
class StorageConfiguration {

  private static final Logger log = LoggerFactory.getLogger(StorageConfiguration.class);
  static final String DEVELOPMENT_SECRET = "local-development-file-signing-secret";

  @Bean
  BlobUrls blobUrls(
      @Value("${pharmacy.files.signing-secret:" + DEVELOPMENT_SECRET + "}") String secret,
      Clock clock) {
    if (DEVELOPMENT_SECRET.equals(secret)) {
      log.warn(
          "Files use the development signing secret; set pharmacy.files.signing-secret in production.");
    }
    return new BlobUrls(new UrlSigner(secret, clock));
  }

  @Bean
  @ConditionalOnProperty(
      name = "pharmacy.files.storage",
      havingValue = "local",
      matchIfMissing = true)
  ObjectStorage localObjectStorage(
      @Value("${pharmacy.files.local.root:target/files}") String root, BlobUrls urls) {
    return new LocalObjectStorage(Path.of(root), urls);
  }

  @Bean
  @ConditionalOnProperty(name = "pharmacy.files.storage", havingValue = "s3")
  ObjectStorage s3ObjectStorage(Environment environment) {
    S3Settings settings = S3Settings.from(environment);
    log.info(
        "Files are in s3://{}/{} ({}{}, server-side encryption {})",
        settings.bucket(),
        settings.prefix(),
        settings.endpoint() == null ? "AWS" : settings.endpoint(),
        settings.region() == null ? "" : ", " + settings.region(),
        settings.serverSideEncryption());
    return new S3ObjectStorage(settings);
  }
}
