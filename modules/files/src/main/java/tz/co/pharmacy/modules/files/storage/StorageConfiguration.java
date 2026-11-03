package tz.co.pharmacy.modules.files.storage;

import java.nio.file.Path;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tz.co.pharmacy.modules.files.ports.outbound.ObjectStorage;

/**
 * Chooses the object store by {@code pharmacy.files.storage}: {@code local} (the default; a
 * directory, {@code pharmacy.files.local.root}) or {@code s3} ({@code pharmacy.files.s3.bucket},
 * {@code .region}, {@code .endpoint}, {@code .access-key}, {@code .secret-key}).
 */
@Configuration(proxyBeanMethods = false)
class StorageConfiguration {

  private static final Logger log = LoggerFactory.getLogger(StorageConfiguration.class);
  static final String DEVELOPMENT_SECRET = "local-development-file-signing-secret";

  @Bean
  @ConditionalOnProperty(
      name = "pharmacy.files.storage",
      havingValue = "local",
      matchIfMissing = true)
  ObjectStorage localObjectStorage(
      @Value("${pharmacy.files.local.root:target/files}") String root,
      @Value("${pharmacy.files.signing-secret:" + DEVELOPMENT_SECRET + "}") String secret,
      Clock clock) {
    if (DEVELOPMENT_SECRET.equals(secret)) {
      log.warn(
          "Files use the development signing secret; set pharmacy.files.signing-secret in production.");
    }
    return new LocalObjectStorage(Path.of(root), new UrlSigner(secret, clock));
  }

  @Bean
  @ConditionalOnProperty(name = "pharmacy.files.storage", havingValue = "s3")
  ObjectStorage s3ObjectStorage(
      @Value("${pharmacy.files.s3.bucket}") String bucket,
      @Value("${pharmacy.files.s3.region:}") String region,
      @Value("${pharmacy.files.s3.endpoint:}") String endpoint,
      @Value("${pharmacy.files.s3.access-key:}") String accessKey,
      @Value("${pharmacy.files.s3.secret-key:}") String secretKey) {
    return new S3ObjectStorage(bucket, region, endpoint, accessKey, secretKey);
  }
}
