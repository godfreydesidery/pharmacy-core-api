package tz.co.pharmacy.modules.files.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import tz.co.pharmacy.modules.files.ports.outbound.ObjectStorage;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * An S3-compatible bucket: AWS S3, or MinIO, Cloudflare R2 and the like through a custom endpoint
 * (path-style by default then). Every instance of the API sees the same bucket, so any number of
 * them can run side by side.
 *
 * <ul>
 *   <li><b>Credentials</b> come from the AWS default provider chain: the ECS task role in
 *       production, environment variables or a profile elsewhere. None are configured here.
 *   <li><b>Uploads</b> stream: up to {@link #SINGLE_PUT_MAX} of known size in one PutObject
 *       straight from the stream; larger, or of unknown size, as a multipart upload of {@link
 *       #PART_SIZE} parts, so at most one part is in memory. A failed multipart upload is aborted.
 *   <li><b>Encryption at rest</b>: every object is written with SSE-S3 ({@code AES256}), or SSE-KMS
 *       when a KMS key is configured ({@link S3Settings}).
 *   <li><b>Downloads</b> are presigned GETs that set the response's Content-Type,
 *       Content-Disposition and Cache-Control, so the browser shows or saves the file under its
 *       name. The bucket needs no CORS rule: the browser only navigates to them or shows them in an
 *       {@code <img>}; uploads go through the API.
 * </ul>
 */
public class S3ObjectStorage implements ObjectStorage, AutoCloseable {

  /** Known-size bodies up to this go in one PutObject. */
  static final long SINGLE_PUT_MAX = 16L * 1024 * 1024;

  /** The part size of a multipart upload (S3 wants at least 5 MiB but for the last part). */
  static final int PART_SIZE = 8 * 1024 * 1024;

  /** The user metadata the copy from local disk records each object's SHA-256 (hex) under. */
  public static final String SHA256_METADATA = "sha256";

  private final S3Settings settings;
  private final S3Client client;
  private final S3Presigner presigner;

  public S3ObjectStorage(S3Settings settings) {
    this(settings, DefaultCredentialsProvider.builder().build());
  }

  public S3ObjectStorage(S3Settings settings, AwsCredentialsProvider credentials) {
    this.settings = settings;
    Region region = region(settings);
    S3Configuration configuration =
        S3Configuration.builder().pathStyleAccessEnabled(settings.pathStyle()).build();
    S3ClientBuilder clientBuilder =
        S3Client.builder()
            .region(region)
            .credentialsProvider(credentials)
            .serviceConfiguration(configuration)
            .httpClientBuilder(
                UrlConnectionHttpClient.builder()
                    .connectionTimeout(Duration.ofSeconds(5))
                    .socketTimeout(Duration.ofSeconds(30)))
            // A store that hangs must not hold a request: each try gives up after 20 s, the whole
            // call (with the SDK's retries) after 60 s. A download streams after the call returns.
            .overrideConfiguration(
                ClientOverrideConfiguration.builder()
                    .apiCallAttemptTimeout(Duration.ofSeconds(20))
                    .apiCallTimeout(Duration.ofSeconds(60))
                    .build());
    S3Presigner.Builder presignerBuilder =
        S3Presigner.builder()
            .region(region)
            .credentialsProvider(credentials)
            .serviceConfiguration(configuration);
    if (settings.endpoint() != null) {
      URI endpoint = URI.create(settings.endpoint());
      clientBuilder
          .endpointOverride(endpoint)
          // Other S3-compatible stores do not all take the SDK's default CRC trailers.
          .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
          .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
      presignerBuilder.endpointOverride(endpoint);
    }
    this.client = clientBuilder.build();
    this.presigner = presignerBuilder.build();
  }

  private static Region region(S3Settings settings) {
    if (settings.region() != null) {
      return Region.of(settings.region());
    }
    try {
      return new DefaultAwsRegionProviderChain().getRegion();
    } catch (SdkException none) {
      if (settings.endpoint() != null) {
        return Region.US_EAST_1;
      }
      throw new IllegalStateException(
          "Set pharmacy.files.s3.region (PHARMACY_FILES_S3_REGION), such as af-south-1", none);
    }
  }

  @Override
  public String name() {
    return "s3";
  }

  public S3Settings settings() {
    return settings;
  }

  @Override
  public String presignGet(String key, String contentType, String disposition, Duration ttl) {
    return presigner
        .presignGetObject(
            request ->
                request
                    .signatureDuration(ttl)
                    .getObjectRequest(
                        get ->
                            get.bucket(settings.bucket())
                                .key(settings.objectKey(key))
                                .responseContentType(contentType)
                                .responseContentDisposition(disposition)
                                .responseCacheControl("private, max-age=" + ttl.toSeconds())))
        .url()
        .toString();
  }

  @Override
  public void put(String key, String contentType, InputStream content, long size) {
    put(key, contentType, content, size, Map.of());
  }

  /** As {@link #put(String, String, InputStream, long)}, with user metadata on the object. */
  public void put(
      String key,
      String contentType,
      InputStream content,
      long size,
      Map<String, String> metadata) {
    try {
      if (size >= 0 && size <= SINGLE_PUT_MAX) {
        client.putObject(
            putRequest(key, contentType, metadata), RequestBody.fromInputStream(content, size));
        return;
      }
      byte[] first = content.readNBytes(PART_SIZE);
      if (first.length < PART_SIZE) {
        client.putObject(putRequest(key, contentType, metadata), body(first));
        return;
      }
      multipart(key, contentType, metadata, first, content);
    } catch (IOException exception) {
      throw new UncheckedIOException("could not store " + key, exception);
    }
  }

  private void multipart(
      String key, String contentType, Map<String, String> metadata, byte[] first, InputStream rest)
      throws IOException {
    CreateMultipartUploadRequest.Builder create =
        CreateMultipartUploadRequest.builder()
            .bucket(settings.bucket())
            .key(settings.objectKey(key))
            .contentType(contentType)
            .metadata(metadata);
    if (settings.encrypted()) {
      create.serverSideEncryption(ServerSideEncryption.fromValue(settings.serverSideEncryption()));
      if (settings.kmsKeyId() != null) {
        create.ssekmsKeyId(settings.kmsKeyId());
      }
    }
    String uploadId = client.createMultipartUpload(create.build()).uploadId();
    try {
      List<CompletedPart> parts = new ArrayList<>();
      byte[] chunk = first;
      while (chunk.length > 0) {
        int number = parts.size() + 1;
        String etag =
            client
                .uploadPart(
                    UploadPartRequest.builder()
                        .bucket(settings.bucket())
                        .key(settings.objectKey(key))
                        .uploadId(uploadId)
                        .partNumber(number)
                        .contentLength((long) chunk.length)
                        .build(),
                    body(chunk))
                .eTag();
        parts.add(CompletedPart.builder().partNumber(number).eTag(etag).build());
        chunk = chunk.length < PART_SIZE ? new byte[0] : rest.readNBytes(PART_SIZE);
      }
      client.completeMultipartUpload(
          complete ->
              complete
                  .bucket(settings.bucket())
                  .key(settings.objectKey(key))
                  .uploadId(uploadId)
                  .multipartUpload(upload -> upload.parts(parts)));
    } catch (IOException | RuntimeException failure) {
      try {
        client.abortMultipartUpload(
            abort ->
                abort.bucket(settings.bucket()).key(settings.objectKey(key)).uploadId(uploadId));
      } catch (RuntimeException alsoFailed) {
        // the bucket's lifecycle rule removes incomplete multipart uploads after a day
        failure.addSuppressed(alsoFailed);
      }
      throw failure;
    }
  }

  /** A body of a buffer, read again from the start if the SDK retries, without a copy. */
  private static RequestBody body(byte[] bytes) {
    return RequestBody.fromContentProvider(
        () -> new ByteArrayInputStream(bytes), bytes.length, "application/octet-stream");
  }

  private PutObjectRequest putRequest(
      String key, String contentType, Map<String, String> metadata) {
    PutObjectRequest.Builder put =
        PutObjectRequest.builder()
            .bucket(settings.bucket())
            .key(settings.objectKey(key))
            .contentType(contentType)
            .metadata(metadata);
    if (settings.encrypted()) {
      put.serverSideEncryption(ServerSideEncryption.fromValue(settings.serverSideEncryption()));
      if (settings.kmsKeyId() != null) {
        put.ssekmsKeyId(settings.kmsKeyId());
      }
    }
    return put.build();
  }

  @Override
  public boolean exists(String key) {
    return head(key).isPresent();
  }

  @Override
  public long size(String key) {
    return head(key).orElseThrow(() -> Refusal.notFound("file_not_found", "No such file.")).size();
  }

  /** The object's size, user metadata and encryption, or empty when there is none. */
  public Optional<Head> head(String key) {
    try {
      HeadObjectResponse head =
          client.headObject(
              request -> request.bucket(settings.bucket()).key(settings.objectKey(key)));
      return Optional.of(
          new Head(
              head.contentLength(),
              head.metadata(),
              head.serverSideEncryptionAsString(),
              head.ssekmsKeyId()));
    } catch (NoSuchKeyException missing) {
      return Optional.empty();
    } catch (S3Exception exception) {
      if (exception.statusCode() == 404) {
        return Optional.empty();
      }
      throw exception;
    }
  }

  @Override
  public InputStream get(String key) {
    try {
      return client.getObject(
          request -> request.bucket(settings.bucket()).key(settings.objectKey(key)));
    } catch (NoSuchKeyException missing) {
      throw Refusal.notFound("file_not_found", "No such file.");
    } catch (S3Exception exception) {
      if (exception.statusCode() == 404) {
        throw Refusal.notFound("file_not_found", "No such file.");
      }
      throw exception;
    }
  }

  @Override
  public void delete(String key) {
    client.deleteObject(request -> request.bucket(settings.bucket()).key(settings.objectKey(key)));
  }

  @Override
  public void close() {
    client.close();
    presigner.close();
  }

  /** What a HEAD of an object says. */
  public record Head(
      long size, Map<String, String> metadata, String serverSideEncryption, String kmsKeyId) {}
}
