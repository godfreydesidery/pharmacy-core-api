package tz.co.pharmacy.modules.files.storage;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An in-process S3-compatible server for tests, on the JDK's own HTTP server: path-style requests
 * ({@code /bucket/key}), any bucket, no signature checks. It speaks enough of the S3 REST API for
 * {@link S3ObjectStorage}: PutObject (plain or aws-chunked bodies), GetObject with the {@code
 * response-*} overrides of a presigned GET, HeadObject, DeleteObject, and multipart uploads
 * (create, upload part, complete with S3's 5 MiB minimum part size, abort). It keeps every object's
 * headers (content type, server-side encryption, user metadata) so a test can check what was sent.
 */
public final class FakeS3 implements AutoCloseable {

  /** An object's bytes and the headers it was stored with (lower-case names). */
  public record StoredObject(byte[] bytes, Map<String, String> headers) {}

  private static final long MIN_PART = 5L * 1024 * 1024;
  private static final Pattern PART_NUMBER = Pattern.compile("<PartNumber>(\\d+)</PartNumber>");

  private final HttpServer server;
  private final ExecutorService executor = Executors.newCachedThreadPool();
  private final Map<String, StoredObject> objects = new ConcurrentHashMap<>();
  private final Map<String, Upload> uploads = new ConcurrentHashMap<>();
  private final List<String> requests = Collections.synchronizedList(new ArrayList<>());

  private record Upload(String path, Map<String, String> headers, Map<Integer, byte[]> parts) {}

  private FakeS3() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/", this::handle);
    server.setExecutor(executor);
    server.start();
  }

  public static FakeS3 start() {
    try {
      return new FakeS3();
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  public String endpoint() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  public Optional<StoredObject> object(String bucket, String key) {
    return Optional.ofNullable(objects.get(bucket + "/" + key));
  }

  /** The keys of a bucket, in order. */
  public List<String> keys(String bucket) {
    return objects.keySet().stream()
        .filter(path -> path.startsWith(bucket + "/"))
        .map(path -> path.substring(bucket.length() + 1))
        .sorted()
        .toList();
  }

  public int openUploads() {
    return uploads.size();
  }

  /** Every request so far, as {@code METHOD /bucket/key?query-names}. */
  public List<String> requests() {
    synchronized (requests) {
      return List.copyOf(requests);
    }
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
  }

  private void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      URI uri = exchange.getRequestURI();
      String path = uri.getPath().substring(1);
      Map<String, String> query = query(uri.getRawQuery());
      String method = exchange.getRequestMethod();
      requests.add(method + " /" + path + (query.isEmpty() ? "" : "?" + query.keySet()));
      byte[] body = body(exchange);
      if (!path.contains("/") || path.endsWith("/") && path.indexOf('/') == path.length() - 1) {
        // A bucket-level request (CreateBucket, HeadBucket): every bucket exists.
        send(exchange, 200, new byte[0], "application/xml");
        return;
      }
      switch (method) {
        case "PUT" -> put(exchange, path, query, body);
        case "POST" -> post(exchange, path, query, body);
        case "GET" -> get(exchange, path, query, true);
        case "HEAD" -> get(exchange, path, query, false);
        case "DELETE" -> delete(exchange, path, query);
        default -> error(exchange, 405, "MethodNotAllowed");
      }
    } catch (RuntimeException failure) {
      error(exchange, 500, "InternalError");
    }
  }

  private void put(HttpExchange exchange, String path, Map<String, String> query, byte[] body)
      throws IOException {
    if (query.containsKey("uploadId")) {
      Upload upload = uploads.get(query.get("uploadId"));
      if (upload == null) {
        error(exchange, 404, "NoSuchUpload");
        return;
      }
      int number = Integer.parseInt(query.get("partNumber"));
      upload.parts().put(number, body);
      exchange.getResponseHeaders().set("ETag", "\"part-" + number + "\"");
      send(exchange, 200, new byte[0], null);
      return;
    }
    Map<String, String> headers = kept(exchange.getRequestHeaders());
    objects.put(path, new StoredObject(body, headers));
    exchange.getResponseHeaders().set("ETag", "\"" + UUID.randomUUID() + "\"");
    echoEncryption(exchange, headers);
    send(exchange, 200, new byte[0], null);
  }

  private void post(HttpExchange exchange, String path, Map<String, String> query, byte[] body)
      throws IOException {
    String bucket = path.substring(0, path.indexOf('/'));
    String key = path.substring(path.indexOf('/') + 1);
    if (query.containsKey("uploads")) {
      String id = UUID.randomUUID().toString();
      Map<String, String> headers = kept(exchange.getRequestHeaders());
      uploads.put(id, new Upload(path, headers, new ConcurrentHashMap<>()));
      echoEncryption(exchange, headers);
      xml(
          exchange,
          200,
          "<InitiateMultipartUploadResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
              + "<Bucket>%s</Bucket><Key>%s</Key><UploadId>%s</UploadId>".formatted(bucket, key, id)
              + "</InitiateMultipartUploadResult>");
      return;
    }
    if (query.containsKey("uploadId")) {
      Upload upload = uploads.get(query.get("uploadId"));
      if (upload == null) {
        error(exchange, 404, "NoSuchUpload");
        return;
      }
      List<Integer> numbers = new ArrayList<>();
      Matcher matcher = PART_NUMBER.matcher(new String(body, StandardCharsets.UTF_8));
      while (matcher.find()) {
        numbers.add(Integer.parseInt(matcher.group(1)));
      }
      ByteArrayOutputStream whole = new ByteArrayOutputStream();
      for (int i = 0; i < numbers.size(); i++) {
        byte[] part = upload.parts().get(numbers.get(i));
        if (part == null) {
          error(exchange, 400, "InvalidPart");
          return;
        }
        if (i < numbers.size() - 1 && part.length < MIN_PART) {
          error(exchange, 400, "EntityTooSmall");
          return;
        }
        whole.write(part);
      }
      uploads.remove(query.get("uploadId"));
      objects.put(path, new StoredObject(whole.toByteArray(), upload.headers()));
      xml(
          exchange,
          200,
          "<CompleteMultipartUploadResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
              + "<Bucket>%s</Bucket><Key>%s</Key><ETag>\"whole-%d\"</ETag>"
                  .formatted(bucket, key, numbers.size())
              + "</CompleteMultipartUploadResult>");
      return;
    }
    error(exchange, 400, "InvalidRequest");
  }

  private void get(HttpExchange exchange, String path, Map<String, String> query, boolean body)
      throws IOException {
    StoredObject object = objects.get(path);
    if (object == null) {
      if (body) {
        error(exchange, 404, "NoSuchKey");
      } else {
        exchange.sendResponseHeaders(404, -1);
      }
      return;
    }
    Headers out = exchange.getResponseHeaders();
    out.set(
        "Content-Type",
        query.getOrDefault(
            "response-content-type",
            object.headers().getOrDefault("content-type", "application/octet-stream")));
    if (query.containsKey("response-content-disposition")) {
      out.set("Content-Disposition", query.get("response-content-disposition"));
    }
    if (query.containsKey("response-cache-control")) {
      out.set("Cache-Control", query.get("response-cache-control"));
    }
    object
        .headers()
        .forEach(
            (name, value) -> {
              if (name.startsWith("x-amz-meta-")) {
                out.set(name, value);
              }
            });
    echoEncryption(exchange, object.headers());
    out.set("ETag", "\"etag\"");
    if (body) {
      send(exchange, 200, object.bytes(), null);
    } else {
      out.set("Content-Length", Long.toString(object.bytes().length));
      exchange.sendResponseHeaders(200, -1);
    }
  }

  private void delete(HttpExchange exchange, String path, Map<String, String> query)
      throws IOException {
    if (query.containsKey("uploadId")) {
      uploads.remove(query.get("uploadId"));
    } else {
      objects.remove(path);
    }
    exchange.sendResponseHeaders(204, -1);
  }

  private static void echoEncryption(HttpExchange exchange, Map<String, String> headers) {
    for (String name :
        List.of("x-amz-server-side-encryption", "x-amz-server-side-encryption-aws-kms-key-id")) {
      if (headers.containsKey(name)) {
        exchange.getResponseHeaders().set(name, headers.get(name));
      }
    }
  }

  /** The headers an object keeps: its type, its encryption and its user metadata. */
  private static Map<String, String> kept(Headers headers) {
    Map<String, String> kept = new TreeMap<>();
    headers.forEach(
        (name, values) -> {
          String lower = name.toLowerCase(Locale.ROOT);
          if (lower.equals("content-type")
              || lower.startsWith("x-amz-server-side-encryption")
              || lower.startsWith("x-amz-meta-")) {
            kept.put(lower, values.get(0));
          }
        });
    return kept;
  }

  /** The request body, decoded when the SDK sent it aws-chunked (signed or with trailers). */
  private static byte[] body(HttpExchange exchange) throws IOException {
    byte[] raw;
    try (InputStream in = exchange.getRequestBody()) {
      raw = in.readAllBytes();
    }
    String sha = exchange.getRequestHeaders().getFirst("x-amz-content-sha256");
    String encoding = exchange.getRequestHeaders().getFirst("Content-Encoding");
    boolean chunked =
        (sha != null && sha.startsWith("STREAMING-"))
            || (encoding != null && encoding.contains("aws-chunked"));
    return chunked ? dechunk(raw) : raw;
  }

  private static byte[] dechunk(byte[] raw) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int at = 0;
    while (at < raw.length) {
      int lineEnd = indexOfCrlf(raw, at);
      String line = new String(raw, at, lineEnd - at, StandardCharsets.US_ASCII);
      int semicolon = line.indexOf(';');
      int size = Integer.parseInt(semicolon < 0 ? line.trim() : line.substring(0, semicolon), 16);
      at = lineEnd + 2;
      if (size == 0) {
        break;
      }
      out.write(raw, at, size);
      at += size + 2;
    }
    return out.toByteArray();
  }

  private static int indexOfCrlf(byte[] bytes, int from) {
    for (int i = from; i + 1 < bytes.length; i++) {
      if (bytes[i] == '\r' && bytes[i + 1] == '\n') {
        return i;
      }
    }
    return bytes.length;
  }

  private static Map<String, String> query(String raw) {
    Map<String, String> query = new LinkedHashMap<>();
    if (raw == null || raw.isEmpty()) {
      return query;
    }
    for (String pair : raw.split("&")) {
      int equals = pair.indexOf('=');
      String name =
          URLDecoder.decode(equals < 0 ? pair : pair.substring(0, equals), StandardCharsets.UTF_8);
      String value =
          equals < 0 ? "" : URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
      query.put(name, value);
    }
    return query;
  }

  private static void xml(HttpExchange exchange, int status, String xml) throws IOException {
    send(
        exchange,
        status,
        ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + xml).getBytes(StandardCharsets.UTF_8),
        "application/xml");
  }

  private static void error(HttpExchange exchange, int status, String code) throws IOException {
    xml(
        exchange,
        status,
        "<Error><Code>" + code + "</Code><Message>" + code + "</Message></Error>");
  }

  private static void send(HttpExchange exchange, int status, byte[] body, String contentType)
      throws IOException {
    if (contentType != null) {
      exchange.getResponseHeaders().set("Content-Type", contentType);
    }
    exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
    if (body.length > 0) {
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(body);
      }
    }
  }
}
