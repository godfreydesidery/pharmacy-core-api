package tz.co.pharmacy.modules.files.storage;

import java.util.Locale;
import org.springframework.core.env.Environment;

/**
 * Where and how the S3-compatible store keeps the files, from {@code pharmacy.files.s3.*}. There
 * are no access keys here: credentials come from the AWS default provider chain (the ECS task role
 * in production; {@code AWS_ACCESS_KEY_ID}/{@code AWS_SECRET_ACCESS_KEY} or a profile elsewhere).
 *
 * @param bucket the bucket (required)
 * @param region the region, such as {@code af-south-1}; blank: the SDK's region chain ({@code
 *     AWS_REGION}), or {@code us-east-1} with a custom endpoint
 * @param endpoint a custom endpoint for MinIO, R2 and the like ({@code http://localhost:9000});
 *     blank for AWS
 * @param pathStyle path-style requests ({@code endpoint/bucket/key}); on by default with a custom
 *     endpoint
 * @param prefix a key prefix ({@code files/}), so a bucket can hold more than this store; blank for
 *     none
 * @param serverSideEncryption {@code AES256} (SSE-S3), {@code aws:kms} (SSE-KMS) or {@code none} (a
 *     store without encryption support, such as MinIO without a KMS)
 * @param kmsKeyId the KMS key of SSE-KMS (id, ARN or alias); blank: the bucket's AWS-managed key
 */
public record S3Settings(
    String bucket,
    String region,
    String endpoint,
    boolean pathStyle,
    String prefix,
    String serverSideEncryption,
    String kmsKeyId) {

  public static final String SSE_S3 = "AES256";
  public static final String SSE_KMS = "aws:kms";
  public static final String SSE_NONE = "none";

  public S3Settings {
    if (bucket == null || bucket.isBlank()) {
      throw new IllegalStateException(
          "pharmacy.files.s3.bucket (PHARMACY_FILES_S3_BUCKET) is required with S3 storage");
    }
    bucket = bucket.trim();
    region = blankToNull(region);
    endpoint = blankToNull(endpoint);
    kmsKeyId = blankToNull(kmsKeyId);
    prefix = normalPrefix(prefix);
    serverSideEncryption = encryption(serverSideEncryption, kmsKeyId);
  }

  /** The settings under {@code pharmacy.files.s3}. */
  public static S3Settings from(Environment environment) {
    String endpoint = environment.getProperty("pharmacy.files.s3.endpoint", "");
    String pathStyle = environment.getProperty("pharmacy.files.s3.path-style", "");
    return new S3Settings(
        environment.getProperty("pharmacy.files.s3.bucket", ""),
        environment.getProperty("pharmacy.files.s3.region", ""),
        endpoint,
        pathStyle.isBlank() ? !endpoint.isBlank() : Boolean.parseBoolean(pathStyle.trim()),
        environment.getProperty("pharmacy.files.s3.prefix", ""),
        environment.getProperty("pharmacy.files.s3.server-side-encryption", ""),
        environment.getProperty("pharmacy.files.s3.kms-key-id", ""));
  }

  /** The bucket key of a store key: the prefix, then the key. */
  public String objectKey(String key) {
    return prefix + key;
  }

  public boolean encrypted() {
    return !SSE_NONE.equals(serverSideEncryption);
  }

  private static String encryption(String requested, String kmsKeyId) {
    String value = requested == null ? "" : requested.trim();
    if (value.isEmpty()) {
      return kmsKeyId == null ? SSE_S3 : SSE_KMS;
    }
    String lower = value.toLowerCase(Locale.ROOT);
    return switch (lower) {
      case "aes256", "sse-s3" -> SSE_S3;
      case "aws:kms", "sse-kms", "kms" -> SSE_KMS;
      case "none", "off", "false" -> SSE_NONE;
      default ->
          throw new IllegalStateException(
              "pharmacy.files.s3.server-side-encryption must be AES256, aws:kms or none, not '"
                  + value
                  + "'");
    };
  }

  private static String normalPrefix(String prefix) {
    String value = prefix == null ? "" : prefix.trim();
    while (value.startsWith("/")) {
      value = value.substring(1);
    }
    if (value.contains("..")) {
      throw new IllegalStateException("pharmacy.files.s3.prefix may not contain '..'");
    }
    return value.isEmpty() || value.endsWith("/") ? value : value + "/";
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
