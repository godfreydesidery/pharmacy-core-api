package tz.co.pharmacy.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tz.co.pharmacy.PharmacyApplication;
import tz.co.pharmacy.platform.composition.LocalDatabase;

/**
 * Runs the whole application on a random port against an embedded PostgreSQL with the production
 * roles: the application connects as a role that cannot bypass row-level security, so these tests
 * exercise tenant isolation exactly as production does.
 */
@SpringBootTest(
    classes = PharmacyApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTest {

  private static Map<String, String> database;

  @Value("${local.server.port}")
  protected int port;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    synchronized (IntegrationTest.class) {
      if (database == null) {
        try {
          Path directory = Files.createTempDirectory("pharmacy-test-postgres");
          database = LocalDatabase.start(directory, freePort());
        } catch (IOException exception) {
          throw new UncheckedIOException(exception);
        }
      }
    }
    database.forEach((key, value) -> registry.add(key, () -> value));
  }

  protected Api api() {
    return new Api("http://localhost:" + port);
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
