package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tz.co.pharmacy.modules.files.storage.FakeS3;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.IntegrationTest;

/**
 * The whole API on S3 storage ({@code pharmacy.files.storage=s3}, a custom endpoint, path-style, a
 * key prefix, credentials from the AWS default chain), against the in-process S3-compatible {@link
 * FakeS3}: the browser's upload flow goes through the API's signed blob route and lands in the
 * bucket encrypted, downloads are presigned GETs of the bucket with the file's name, a direct
 * upload lands there too and a delete removes the object. Its own application context, closed after
 * the class, so no other test's files go to this bucket.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FilesOnS3Test extends IntegrationTest {

  private static final String BUCKET = "pharmacy-files";
  private static final String PREFIX = "files/";
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static FakeS3 s3;

  @DynamicPropertySource
  static void s3(DynamicPropertyRegistry registry) {
    synchronized (FilesOnS3Test.class) {
      if (s3 == null) {
        s3 = FakeS3.start();
      }
    }
    // The SDK's default chain reads these first; in production it finds the ECS task role.
    System.setProperty("aws.accessKeyId", "test-key");
    System.setProperty("aws.secretAccessKey", "test-secret");
    registry.add("pharmacy.files.storage", () -> "s3");
    registry.add("pharmacy.files.s3.bucket", () -> BUCKET);
    registry.add("pharmacy.files.s3.region", () -> "af-south-1");
    registry.add("pharmacy.files.s3.endpoint", s3::endpoint);
    registry.add("pharmacy.files.s3.prefix", () -> PREFIX);
  }

  @AfterAll
  static void stop() {
    if (s3 != null) {
      s3.close();
    }
  }

  @Test
  @DisplayName("upload through the API, download from the bucket, delete: all on S3")
  void filesLiveInTheBucket() throws Exception {
    Api anonymous = api();
    Api root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "S3" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    String email = code.toLowerCase() + "@test.local";
    String tenant =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"S3 %s","legalName":"S3 %s Ltd","country":"TZ",
                 "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
                 "headOffice":{"code":"HQ","name":"Head Office"},
                 "admin":{"name":"Admin","email":"%s","password":"Admin#2026x-strong"}}
                """
                    .formatted(code, code, code, email))
            .expect(201)
            .string("$.data.uid");
    Api admin = anonymous.as(anonymous.signIn(email, "Admin#2026x-strong")).in(tenant, null);
    byte[] pdf =
        ("%PDF-1.4\n" + "x".repeat(50_000) + "\n%%EOF\n").getBytes(StandardCharsets.US_ASCII);

    // Begin: the upload link is the API's own signed blob route, not the bucket.
    Api.Response ticket =
        admin
            .post(
                "/api/v1/files/uploads",
                """
                {"name":"Rx 2026-11.pdf","contentType":"application/pdf","size":%d,
                 "ownerType":"TENANT","ownerUid":"%s","purpose":"ATTACHMENT"}
                """
                    .formatted(pdf.length, tenant))
            .expect(201);
    String uploadUrl = ticket.string("$.data.uploadUrl");
    String fileUid = ticket.string("$.data.fileUid");
    assertThat(uploadUrl).startsWith("/api/v1/files/blob/tenants/" + tenant + "/");
    String key = uploadUrl.substring("/api/v1/files/blob/".length(), uploadUrl.indexOf('?'));

    // A body over 10 MB is refused before it is read (413, or the connection is dropped while
    // the client is still sending) and nothing reaches the bucket; then the signed PUT stores the
    // bytes in S3.
    assertThat(put(uploadUrl, "application/pdf", new byte[11 * 1024 * 1024])).isIn(413, -1);
    assertThat(s3.object(BUCKET, PREFIX + key)).isEmpty();
    assertThat(put(uploadUrl, "application/pdf", pdf)).isEqualTo(200);
    FakeS3.StoredObject stored = s3.object(BUCKET, PREFIX + key).orElseThrow();
    assertThat(stored.bytes()).isEqualTo(pdf);
    assertThat(stored.headers())
        .containsEntry("x-amz-server-side-encryption", "AES256")
        .containsEntry("content-type", "application/pdf");

    Api.Response completed =
        admin.post("/api/v1/files/uploads/" + fileUid + "/complete", null).expect(200);
    assertThat(completed.string("$.data.sha256")).isEqualTo(sha256(pdf));

    // The download link is a presigned GET of the bucket, served under the file's name.
    String url = admin.get("/api/v1/files/" + fileUid + "/url").expect(200).string("$.data.url");
    assertThat(url).startsWith(s3.endpoint() + "/" + BUCKET + "/" + PREFIX + key + "?");
    HttpResponse<byte[]> download =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(url)).build(),
            HttpResponse.BodyHandlers.ofByteArray());
    assertThat(download.statusCode()).isEqualTo(200);
    assertThat(download.body()).isEqualTo(pdf);
    assertThat(download.headers().firstValue("Content-Type")).hasValue("application/pdf");
    assertThat(download.headers().firstValue("Content-Disposition").orElseThrow())
        .startsWith("inline; filename=\"Rx 2026-11.pdf\"");

    // A small file posted as multipart goes to the bucket too.
    String logo =
        admin
            .upload(
                "/api/v1/files?ownerType=TENANT&ownerUid=" + tenant + "&purpose=LOGO",
                "logo.png",
                "image/png",
                new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0})
            .expect(201)
            .string("$.data.uid");
    assertThat(s3.keys(BUCKET))
        .filteredOn(stored1 -> stored1.endsWith("/" + logo))
        .singleElement()
        .asString()
        .startsWith(PREFIX + "tenants/" + tenant + "/");

    // Deleting the file removes the object once the record is committed as deleted.
    admin.delete("/api/v1/files/" + fileUid).expect(200);
    assertThat(s3.object(BUCKET, PREFIX + key)).isEmpty();
    admin.get("/api/v1/files/" + fileUid + "/url").expect(404);
  }

  /** The status of a PUT to the API, or -1 when the server closed the connection on it. */
  private int put(String path, String contentType, byte[] body) throws InterruptedException {
    try {
      return HTTP.send(
              HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                  .header("Content-Type", contentType)
                  .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                  .build(),
              HttpResponse.BodyHandlers.discarding())
          .statusCode();
    } catch (IOException closed) {
      return -1;
    }
  }

  private static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }
}
