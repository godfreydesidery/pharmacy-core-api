package tz.co.pharmacy.support;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.BooleanSupplier;

/** A small HTTP client for the tests: a session token, a tenant and a branch, and JSON paths. */
public final class Api {

  private static final HttpClient CLIENT =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  private final String base;
  private String token;
  private String tenant;
  private String branch;

  Api(String base) {
    this.base = base;
  }

  public Api as(String token) {
    Api copy = new Api(base);
    copy.token = token;
    copy.tenant = tenant;
    copy.branch = branch;
    return copy;
  }

  public Api in(String tenantUid, String branchUid) {
    Api copy = new Api(base);
    copy.token = token;
    copy.tenant = tenantUid;
    copy.branch = branchUid;
    return copy;
  }

  public Response get(String path) {
    return send("GET", path, null);
  }

  public Response post(String path, String json) {
    return send("POST", path, json);
  }

  public Response put(String path, String json) {
    return send("PUT", path, json);
  }

  public Response delete(String path) {
    return send("DELETE", path, null);
  }

  /** Posts one file as multipart/form-data, as a browser's file input does, under {@code file}. */
  public Response upload(String path, String fileName, byte[] content) {
    return upload(path, fileName, "application/octet-stream", content);
  }

  /** A multipart upload of one part named file, sent as {@code contentType}. */
  public Response upload(String path, String fileName, String contentType, byte[] content) {
    String boundary = "----pharmacy" + System.nanoTime();
    String crlf = "\r\n";
    byte[] head =
        ("--"
                + boundary
                + crlf
                + "Content-Disposition: form-data; name=\"file\"; filename=\""
                + fileName
                + "\""
                + crlf
                + "Content-Type: "
                + contentType
                + crlf
                + crlf)
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] tail =
        (crlf + "--" + boundary + "--" + crlf).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] body = new byte[head.length + content.length + tail.length];
    System.arraycopy(head, 0, body, 0, head.length);
    System.arraycopy(content, 0, body, head.length, content.length);
    System.arraycopy(tail, 0, body, head.length + content.length, tail.length);
    HttpRequest.Builder request = request(path);
    request.header("Content-Type", "multipart/form-data; boundary=" + boundary);
    request.POST(HttpRequest.BodyPublishers.ofByteArray(body));
    return call(request);
  }

  /** The body of a GET as bytes, such as a workbook; fails unless it answers 200. */
  public byte[] download(String path) {
    try {
      HttpResponse<byte[]> response =
          CLIENT.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() != 200) {
        throw new AssertionError(
            "expected 200 but was " + response.statusCode() + ": " + new String(response.body()));
      }
      return response.body();
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }

  /** Signs in and returns the session token. */
  public String signIn(String email, String password) {
    Response response =
        post(
            "/api/v1/auth/sign-in",
            "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    response.expect(201);
    return response.string("$.data.token");
  }

  private HttpRequest.Builder request(String path) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(120));
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    if (tenant != null) {
      request.header("X-Tenant-Id", tenant);
    }
    if (branch != null) {
      request.header("X-Branch-Id", branch);
    }
    return request;
  }

  private Response send(String method, String path, String json) {
    HttpRequest.Builder request = request(path);
    if (json != null) {
      request.header("Content-Type", "application/json");
      request.method(method, HttpRequest.BodyPublishers.ofString(json));
    } else {
      request.method(method, HttpRequest.BodyPublishers.noBody());
    }
    return call(request);
  }

  private static Response call(HttpRequest.Builder request) {
    try {
      HttpResponse<String> response =
          CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
      return new Response(response.statusCode(), response.body());
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }

  /** Polls until {@code value} gives something other than null, and returns it. */
  public static <T> T eventuallyValue(java.util.function.Supplier<T> value) {
    java.util.concurrent.atomic.AtomicReference<T> found =
        new java.util.concurrent.atomic.AtomicReference<>();
    eventually(
        () -> {
          found.set(value.get());
          return found.get() != null;
        });
    return found.get();
  }

  /** Polls until the condition holds: event listeners run after commit, on their own threads. */
  public static void eventually(BooleanSupplier condition) {
    long deadline = System.currentTimeMillis() + 15_000;
    while (System.currentTimeMillis() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      try {
        Thread.sleep(200);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        return;
      }
    }
    throw new AssertionError("condition not met within 15 seconds");
  }

  public record Response(int status, String body) {

    public Response expect(int expected) {
      if (status != expected) {
        throw new AssertionError("expected HTTP " + expected + " but was " + status + ": " + body);
      }
      return this;
    }

    public DocumentContext json() {
      return JsonPath.parse(body);
    }

    public String string(String path) {
      return json().read(path, String.class);
    }

    public <T> T read(String path, Class<T> type) {
      return json().read(path, type);
    }
  }
}
