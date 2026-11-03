package tz.co.pharmacy.modules.notifications.providers;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Sends provider requests with 10 second timeouts; a non-2xx answer is a failure. */
@Component
public class ProviderHttp {

  static final Duration TIMEOUT = Duration.ofSeconds(10);

  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(TIMEOUT)
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  /** Returns the response body. */
  public String send(String provider, ProviderRequest request) {
    try {
      HttpResponse<String> response =
          client.send(request.toHttp(TIMEOUT), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        String body = response.body() == null ? "" : response.body();
        throw new ProviderException(
            provider
                + " answered HTTP "
                + response.statusCode()
                + ": "
                + body.substring(0, Math.min(body.length(), 200)));
      }
      return response.body();
    } catch (IOException exception) {
      throw new ProviderException(
          provider + " is unreachable: " + exception.getMessage(), exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new ProviderException(provider + " call was interrupted", exception);
    }
  }
}
