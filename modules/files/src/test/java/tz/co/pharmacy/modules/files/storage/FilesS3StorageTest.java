package tz.co.pharmacy.modules.files.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import tz.co.pharmacy.modules.files.ports.inbound.FileRules;
import tz.co.pharmacy.modules.files.ports.outbound.ObjectStorage;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * One contract for every store, run against the local directory and against S3: an in-process
 * S3-compatible fake ({@link FakeS3}, always, offline) and, when Docker is running, Adobe's S3Mock
 * in a container (a full S3 REST implementation). The S3 runs use the real {@link S3ObjectStorage}
 * with a custom endpoint and path-style requests, as MinIO or R2 would be reached.
 */
class FilesS3StorageTest {

  static final String BUCKET = "pharmacy-files";
  static final AwsCredentialsProvider CREDENTIALS =
      StaticCredentialsProvider.create(AwsBasicCredentials.create("test-key", "test-secret"));
  static final Random RANDOM = new Random(42);

  static byte[] bytes(int size) {
    byte[] bytes = new byte[size];
    RANDOM.nextBytes(bytes);
    return bytes;
  }

  static String key() {
    return "tenants/" + UUID.randomUUID() + "/2026/11/" + UUID.randomUUID();
  }

  static byte[] read(ObjectStorage storage, String key) throws IOException {
    try (InputStream in = storage.get(key)) {
      return in.readAllBytes();
    }
  }

  /** What every store does. */
  abstract static class Contract {

    abstract ObjectStorage storage();

    @Test
    @DisplayName("a file of known size is stored, found, measured and read back")
    void roundTrip() throws IOException {
      String key = key();
      byte[] content = bytes(70_000);
      storage().put(key, "image/png", new ByteArrayInputStream(content), content.length);
      assertThat(storage().exists(key)).isTrue();
      assertThat(storage().size(key)).isEqualTo(content.length);
      assertThat(read(storage(), key)).isEqualTo(content);
    }

    @Test
    @DisplayName("a body of unknown size (a chunked request) is stored too")
    void unknownSize() throws IOException {
      String key = key();
      byte[] content = bytes(300_000);
      storage().put(key, "application/pdf", new ByteArrayInputStream(content), -1);
      assertThat(read(storage(), key)).isEqualTo(content);
    }

    @Test
    @DisplayName("a large archive streams in parts, whether its size is known or not")
    void largeObjects() throws IOException {
      String known = key();
      byte[] big = bytes(20 * 1024 * 1024 + 123);
      storage().put(known, "application/zip", new ByteArrayInputStream(big), big.length);
      assertThat(storage().size(known)).isEqualTo(big.length);
      assertThat(read(storage(), known)).isEqualTo(big);

      String unknown = key();
      byte[] other = bytes(17 * 1024 * 1024);
      storage().put(unknown, "application/zip", new ByteArrayInputStream(other), -1);
      assertThat(read(storage(), unknown)).isEqualTo(other);
    }

    @Test
    @DisplayName("storing again replaces; deleting removes, and deleting twice does nothing")
    void replaceAndDelete() throws IOException {
      String key = key();
      storage().put(key, "image/png", new ByteArrayInputStream(bytes(10)), 10);
      byte[] second = bytes(20);
      storage().put(key, "image/png", new ByteArrayInputStream(second), 20);
      assertThat(read(storage(), key)).isEqualTo(second);
      storage().delete(key);
      assertThat(storage().exists(key)).isFalse();
      storage().delete(key);
    }

    @Test
    @DisplayName("a missing object is absent: exists false, size and get 404 file_not_found")
    void missing() {
      String key = key();
      assertThat(storage().exists(key)).isFalse();
      assertThatThrownBy(() -> storage().size(key))
          .isInstanceOf(Refusal.class)
          .extracting(thrown -> ((Refusal) thrown).reason())
          .isEqualTo("file_not_found");
      assertThatThrownBy(() -> storage().get(key).close())
          .isInstanceOf(Refusal.class)
          .extracting(thrown -> ((Refusal) thrown).reason())
          .isEqualTo("file_not_found");
    }
  }

  /** What the S3 store does beyond the contract: links, prefix, the copy from local disk. */
  abstract static class S3Contract extends Contract {

    private S3ObjectStorage storage;

    abstract String endpoint();

    /** SSE as the S3 under test accepts it. */
    String serverSideEncryption() {
      return "";
    }

    S3ObjectStorage s3(String prefix, String sse, String kmsKeyId) {
      return new S3ObjectStorage(
          new S3Settings(BUCKET, "af-south-1", endpoint(), true, prefix, sse, kmsKeyId),
          CREDENTIALS);
    }

    @Override
    S3ObjectStorage storage() {
      if (storage == null) {
        storage = s3("", serverSideEncryption(), "");
      }
      return storage;
    }

    @Test
    @DisplayName("a presigned GET serves the bytes with the type and the file name asked for")
    void presignedGet() throws Exception {
      String key = key();
      byte[] content = "%PDF-1.4 scan".getBytes(StandardCharsets.US_ASCII);
      storage().put(key, "application/octet-stream", new ByteArrayInputStream(content), -1);
      String disposition = FileRules.disposition(false, "Prescription 12 – Asha.pdf");
      String url =
          storage().presignGet(key, "application/pdf", disposition, Duration.ofMinutes(15));
      assertThat(url)
          .startsWith(endpoint() + "/" + BUCKET + "/" + key + "?")
          .contains("X-Amz-Expires=900")
          .contains("X-Amz-Signature=")
          .contains("response-content-disposition=");
      HttpResponse<byte[]> response =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(URI.create(url)).GET().build(),
                  HttpResponse.BodyHandlers.ofByteArray());
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.body()).isEqualTo(content);
      assertThat(response.headers().firstValue("Content-Type")).hasValue("application/pdf");
      assertThat(response.headers().firstValue("Content-Disposition"))
          .hasValue(
              "inline; filename=\"Prescription 12 _ Asha.pdf\";"
                  + " filename*=UTF-8''Prescription%2012%20%E2%80%93%20Asha.pdf");
    }

    @Test
    @DisplayName("with a prefix the same store keys land under it in the bucket")
    void prefix() throws IOException {
      String prefix = "files-" + UUID.randomUUID().toString().substring(0, 8) + "/";
      String key = key();
      try (S3ObjectStorage prefixed = s3(prefix, serverSideEncryption(), "")) {
        prefixed.put(key, "image/png", new ByteArrayInputStream(bytes(5)), 5);
        assertThat(prefixed.exists(key)).isTrue();
      }
      assertThat(storage().exists(prefix + key)).isTrue();
      assertThat(storage().exists(key)).isFalse();
    }

    @Test
    @DisplayName("the copy from local disk is checked, idempotent and resumable")
    void copyFromLocalDisk(@TempDir Path root) throws IOException {
      String tenant = "tenants/" + UUID.randomUUID();
      Path scan = write(root, tenant + "/2026/11/" + UUID.randomUUID(), bytes(1000));
      Path export =
          write(root, tenant + "/exports/" + UUID.randomUUID() + "/data.zip", bytes(9 << 20));
      Path halfDone = write(root, tenant + "/2026/11/" + UUID.randomUUID(), bytes(77));
      write(root, tenant + "/2026/11/" + UUID.randomUUID() + ".part", bytes(10));
      String prefix = "copy-" + UUID.randomUUID().toString().substring(0, 8) + "/";
      try (S3ObjectStorage target = s3(prefix, serverSideEncryption(), "")) {
        // An earlier run that died after the bytes but before the check left this one behind.
        target.put(
            keyOf(root, halfDone), "application/octet-stream", Files.newInputStream(halfDone), 77);

        LocalToS3Copy.Report first =
            new LocalToS3Copy(root, target, LocalToS3Copy.Verify.HEAD).run();
        assertThat(first.ok()).as(first.failed().toString()).isTrue();
        assertThat(first.files()).isEqualTo(3);
        assertThat(first.copied()).isEqualTo(3);
        assertThat(read(target, keyOf(root, scan))).isEqualTo(Files.readAllBytes(scan));
        assertThat(read(target, keyOf(root, export))).isEqualTo(Files.readAllBytes(export));
        assertThat(target.head(keyOf(root, scan)).orElseThrow().metadata())
            .containsKey(S3ObjectStorage.SHA256_METADATA);

        LocalToS3Copy.Report again =
            new LocalToS3Copy(root, target, LocalToS3Copy.Verify.HEAD).run();
        assertThat(again.copied()).isZero();
        assertThat(again.skipped()).isEqualTo(3);

        // A file written meanwhile (the second pass of the cut-over) is the only one copied.
        Files.write(scan, bytes(1001));
        LocalToS3Copy.Report third =
            new LocalToS3Copy(root, target, LocalToS3Copy.Verify.FULL).run();
        assertThat(third.ok()).isTrue();
        assertThat(third.copied()).isOne();
        assertThat(third.skipped()).isEqualTo(2);
        assertThat(read(target, keyOf(root, scan))).isEqualTo(Files.readAllBytes(scan));
      }
    }

    private static Path write(Path root, String key, byte[] content) throws IOException {
      Path path = root.resolve(key);
      Files.createDirectories(path.getParent());
      return Files.write(path, content);
    }

    private static String keyOf(Path root, Path file) {
      return root.relativize(file).toString().replace('\\', '/');
    }
  }

  @Nested
  @DisplayName("local directory")
  class OnLocalDisk extends Contract {

    @TempDir Path root;
    private LocalObjectStorage storage;

    @Override
    ObjectStorage storage() {
      if (storage == null) {
        storage =
            new LocalObjectStorage(
                root, new BlobUrls(new UrlSigner("a-test-secret-of-length", Clock.systemUTC())));
      }
      return storage;
    }
  }

  @Nested
  @DisplayName("S3 (in-process fake)")
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  class OnFakeS3 extends S3Contract {

    private FakeS3 fake;

    @BeforeAll
    void start() {
      fake = FakeS3.start();
    }

    @AfterAll
    void stop() {
      fake.close();
    }

    @Override
    String endpoint() {
      return fake.endpoint();
    }

    @Test
    @DisplayName("every object is written with SSE-S3, or SSE-KMS with the configured key")
    void encryptionHeaders() throws IOException {
      String small = key();
      storage().put(small, "image/png", new ByteArrayInputStream(bytes(10)), 10);
      assertThat(fake.object(BUCKET, small).orElseThrow().headers())
          .containsEntry("x-amz-server-side-encryption", "AES256")
          .containsEntry("content-type", "image/png");

      try (S3ObjectStorage kms = s3("", "", "alias/pharmacy-files")) {
        String sealed = key();
        kms.put(sealed, "image/png", new ByteArrayInputStream(bytes(10)), 10);
        String big = key();
        kms.put(big, "application/zip", new ByteArrayInputStream(bytes(18 << 20)), -1);
        for (String key : new String[] {sealed, big}) {
          assertThat(fake.object(BUCKET, key).orElseThrow().headers())
              .containsEntry("x-amz-server-side-encryption", "aws:kms")
              .containsEntry("x-amz-server-side-encryption-aws-kms-key-id", "alias/pharmacy-files");
        }
        assertThat(kms.head(big).orElseThrow().serverSideEncryption()).isEqualTo("aws:kms");
      }

      try (S3ObjectStorage plain = s3("", "none", "")) {
        String key = key();
        plain.put(key, "image/png", new ByteArrayInputStream(bytes(10)), 10);
        assertThat(fake.object(BUCKET, key).orElseThrow().headers())
            .doesNotContainKey("x-amz-server-side-encryption");
      }
    }

    @Test
    @DisplayName("a large upload goes in 8 MiB parts, and one that fails half-way is aborted")
    void multipartIsAbortedOnFailure() {
      String key = key();
      storage().put(key, "application/zip", new ByteArrayInputStream(bytes(20 << 20)), 20 << 20);
      assertThat(fake.requests())
          .filteredOn(request -> request.startsWith("PUT /" + BUCKET + "/" + key + "?"))
          .hasSize(3);

      String broken = key();
      InputStream failing =
          new InputStream() {
            private long sent;

            @Override
            public int read() throws IOException {
              if (++sent > 10L << 20) {
                throw new IOException("client went away");
              }
              return 7;
            }
          };
      assertThatThrownBy(() -> storage().put(broken, "application/zip", failing, -1))
          .hasMessageContaining("could not store");
      assertThat(fake.openUploads()).isZero();
      assertThat(storage().exists(broken)).isFalse();
    }
  }

  @Nested
  @DisplayName("S3 (S3Mock container, when Docker is running)")
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  class OnS3MockContainer extends S3Contract {

    private GenericContainer<?> s3mock;

    @BeforeAll
    void start() {
      Assumptions.assumeTrue(dockerAvailable(), "Docker is not running");
      s3mock =
          new GenericContainer<>("adobe/s3mock:4.7.0")
              .withExposedPorts(9090)
              .waitingFor(Wait.forListeningPort())
              .withStartupTimeout(Duration.ofMinutes(2));
      s3mock.start();
      try (S3Client admin =
          S3Client.builder()
              .endpointOverride(URI.create(endpoint()))
              .region(Region.AF_SOUTH_1)
              .credentialsProvider(CREDENTIALS)
              .forcePathStyle(true)
              .httpClient(UrlConnectionHttpClient.create())
              .build()) {
        admin.createBucket(bucket -> bucket.bucket(BUCKET));
      }
    }

    @AfterAll
    void stop() {
      if (s3mock != null) {
        s3mock.stop();
      }
    }

    @Override
    String endpoint() {
      return "http://" + s3mock.getHost() + ":" + s3mock.getMappedPort(9090);
    }

    private static boolean dockerAvailable() {
      try {
        return DockerClientFactory.instance().isDockerAvailable();
      } catch (RuntimeException | Error noDocker) {
        return false;
      }
    }

    @Test
    @DisplayName("S3Mock keeps the SSE-S3 the store asks for")
    void serverSideEncryptionIsAccepted() {
      String key = key();
      storage().put(key, "image/png", new ByteArrayInputStream(bytes(10)), 10);
      assertThat(storage().head(key).orElseThrow().serverSideEncryption()).isEqualTo("AES256");
    }
  }
}
