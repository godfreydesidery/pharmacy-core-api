package tz.co.pharmacy.modules.integrations.application;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * One HTTP/1.1 {@code POST} to addresses already checked, without resolving the host's name again
 * (adversarial review INT-14: a name re-pointed between the check and the connection must not reach
 * a private address). The TCP connection goes to the first checked address that answers; with
 * https, TLS still names the host (SNI) and verifies the certificate against the host's name, as
 * any client does. Only the status line is read; the connection closes after one exchange.
 * Redirects are never followed (the status is returned as it is).
 */
final class PinnedPost {

  /** A status line, or the headers after a 1xx, may be this long at most. */
  private static final int MAX_LINE = 8 * 1024;

  private PinnedPost() {}

  /**
   * Posts {@code body} to {@code uri}, connecting to one of {@code addresses}; the response's
   * status code. {@code timeout} applies to the connection and to each read.
   */
  static int post(
      URI uri,
      List<InetAddress> addresses,
      Map<String, String> headers,
      byte[] body,
      Duration timeout)
      throws IOException {
    if (addresses == null || addresses.isEmpty()) {
      throw new IOException("no checked address to connect to");
    }
    boolean tls = "https".equalsIgnoreCase(uri.getScheme());
    int port = uri.getPort() > 0 ? uri.getPort() : tls ? 443 : 80;
    String host = bare(uri.getHost());
    try (Socket socket = connect(addresses, port, timeout)) {
      Socket channel = tls ? secure(socket, host, port) : socket;
      try {
        OutputStream out = channel.getOutputStream();
        out.write(head(uri, headers, body.length).getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
        return status(channel.getInputStream());
      } finally {
        if (channel != socket) {
          channel.close();
        }
      }
    }
  }

  private static Socket connect(List<InetAddress> addresses, int port, Duration timeout)
      throws IOException {
    IOException last = null;
    for (InetAddress address : addresses) {
      Socket socket = new Socket();
      try {
        socket.connect(new InetSocketAddress(address, port), (int) timeout.toMillis());
        socket.setSoTimeout((int) timeout.toMillis());
        return socket;
      } catch (IOException failed) {
        socket.close();
        last = failed;
      }
    }
    throw last;
  }

  private static Socket secure(Socket socket, String host, int port) throws IOException {
    SSLSocket ssl =
        (SSLSocket)
            ((SSLSocketFactory) SSLSocketFactory.getDefault())
                .createSocket(socket, host, port, true);
    SSLParameters parameters = ssl.getSSLParameters();
    // The certificate must name the host the admin typed, not the address connected to.
    parameters.setEndpointIdentificationAlgorithm("HTTPS");
    if (!literal(host)) {
      parameters.setServerNames(List.of(new SNIHostName(host)));
    }
    ssl.setSSLParameters(parameters);
    ssl.startHandshake();
    return ssl;
  }

  /** The request line and headers, ending with the blank line. */
  static String head(URI uri, Map<String, String> headers, int length) {
    String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
    String target = uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
    StringBuilder head = new StringBuilder("POST ").append(plain(target)).append(" HTTP/1.1\r\n");
    head.append("Host: ").append(plain(uri.getRawAuthority())).append("\r\n");
    headers.forEach(
        (name, value) -> head.append(plain(name)).append(": ").append(plain(value)).append("\r\n"));
    head.append("Content-Length: ").append(length).append("\r\n");
    head.append("Connection: close\r\n\r\n");
    return head.toString();
  }

  /** {@code text} itself; refused when it holds a line break or another control character. */
  private static String plain(String text) {
    if (text == null || text.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
      throw new IllegalArgumentException("a request line or header holds a control character");
    }
    return text;
  }

  /** The status code of the response, after any interim (1xx) ones. */
  private static int status(InputStream in) throws IOException {
    while (true) {
      String line = line(in);
      String[] parts = line.split(" ", 3);
      if (parts.length < 2 || !parts[0].toUpperCase(Locale.ROOT).startsWith("HTTP/1.")) {
        throw new IOException("not an HTTP/1.1 response");
      }
      int status;
      try {
        status = Integer.parseInt(parts[1]);
      } catch (NumberFormatException notANumber) {
        throw new IOException("not an HTTP status: " + parts[1]);
      }
      if (status >= 200 || status == 101) {
        return status;
      }
      // An interim response: skip its headers and read the final one.
      while (!line(in).isEmpty()) {
        // skip
      }
    }
  }

  private static String line(InputStream in) throws IOException {
    ByteArrayOutputStream line = new ByteArrayOutputStream();
    int previous = -1;
    while (true) {
      int b = in.read();
      if (b < 0) {
        throw new IOException("the connection closed before a response");
      }
      if (previous == '\r' && b == '\n') {
        byte[] bytes = line.toByteArray();
        return new String(bytes, 0, bytes.length - 1, StandardCharsets.ISO_8859_1);
      }
      line.write(b);
      if (line.size() > MAX_LINE) {
        throw new IOException("the response line is too long");
      }
      previous = b;
    }
  }

  private static String bare(String host) {
    return host != null && host.startsWith("[") && host.endsWith("]")
        ? host.substring(1, host.length() - 1)
        : host;
  }

  private static boolean literal(String host) {
    return host.contains(":") || host.matches("[0-9.]+");
  }
}
