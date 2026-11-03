package tz.co.pharmacy.modules.notifications.providers;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;

/** An HTTP request to a provider, as plain data so it can be tested without sending it. */
public record ProviderRequest(String method, URI uri, Map<String, String> headers, String body) {

  HttpRequest toHttp(Duration timeout) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
    headers.forEach(builder::header);
    return builder.build();
  }
}
