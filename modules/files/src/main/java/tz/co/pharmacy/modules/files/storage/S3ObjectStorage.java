package tz.co.pharmacy.modules.files.storage;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import tz.co.pharmacy.modules.files.ports.outbound.ObjectStorage;

/**
 * An S3-compatible bucket: AWS S3, MinIO or Cloudflare R2 (set {@code endpoint} for the last two;
 * path-style access is used then). The bucket needs a CORS rule allowing PUT and GET from the web
 * app's origin, since the browser uploads and downloads directly with presigned URLs.
 */
public class S3ObjectStorage implements ObjectStorage {

  private final String bucket;
  private final S3Client client;
  private final S3Presigner presigner;

  public S3ObjectStorage(
      String bucket, String region, String endpoint, String accessKey, String secretKey) {
    this.bucket = bucket;
    AwsCredentialsProvider credentials =
        accessKey == null || accessKey.isBlank()
            ? DefaultCredentialsProvider.create()
            : StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
    Region awsRegion = Region.of(region == null || region.isBlank() ? "us-east-1" : region);
    boolean custom = endpoint != null && !endpoint.isBlank();
    S3Configuration configuration =
        S3Configuration.builder().pathStyleAccessEnabled(custom).build();
    var clientBuilder =
        S3Client.builder()
            .region(awsRegion)
            .credentialsProvider(credentials)
            .serviceConfiguration(configuration)
            // A store that hangs must not hold a request: each try gives up after 20 s, the whole
            // call (with the SDK's retries) after 60 s. Large downloads stream after the call.
            .overrideConfiguration(
                ClientOverrideConfiguration.builder()
                    .apiCallAttemptTimeout(java.time.Duration.ofSeconds(20))
                    .apiCallTimeout(java.time.Duration.ofSeconds(60))
                    .build());
    var presignerBuilder =
        S3Presigner.builder()
            .region(awsRegion)
            .credentialsProvider(credentials)
            .serviceConfiguration(configuration);
    if (custom) {
      clientBuilder.endpointOverride(URI.create(endpoint));
      presignerBuilder.endpointOverride(URI.create(endpoint));
    }
    this.client = clientBuilder.build();
    this.presigner = presignerBuilder.build();
  }

  @Override
  public String name() {
    return "s3";
  }

  @Override
  public String presignPut(String key, String contentType, Duration ttl) {
    return presigner
        .presignPutObject(
            request ->
                request
                    .signatureDuration(ttl)
                    .putObjectRequest(
                        PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(contentType)
                            .build()))
        .url()
        .toString();
  }

  @Override
  public String presignGet(String key, String contentType, Duration ttl) {
    return presigner
        .presignGetObject(
            request ->
                request
                    .signatureDuration(ttl)
                    .getObjectRequest(
                        GetObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .responseContentType(contentType)
                            .build()))
        .url()
        .toString();
  }

  @Override
  public void put(String key, String contentType, InputStream content, long size) {
    client.putObject(
        PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
        RequestBody.fromInputStream(content, size));
  }

  @Override
  public boolean exists(String key) {
    try {
      client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
      return true;
    } catch (NoSuchKeyException missing) {
      return false;
    } catch (S3Exception exception) {
      if (exception.statusCode() == 404) {
        return false;
      }
      throw exception;
    }
  }

  @Override
  public long size(String key) {
    return client
        .headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build())
        .contentLength();
  }

  @Override
  public InputStream get(String key) {
    return client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
  }

  @Override
  public void delete(String key) {
    client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
  }
}
