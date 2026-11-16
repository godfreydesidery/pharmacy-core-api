package tz.co.pharmacy.modules.notifications.providers;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.Locale;
import javax.net.ssl.SSLException;

/**
 * Says in plain words why a provider could not be used, for the delivery log and the test result an
 * administrator reads ("Could not reach Beem (TLS handshake failed)…" rather than "Unrecognized SSL
 * message, plaintext connection?"). The raw failure belongs in the server log.
 */
public final class ProviderErrors {

  private ProviderErrors() {}

  public static String readable(Throwable failure, String provider) {
    String who = provider == null || provider.isBlank() ? "the provider" : provider;
    for (Throwable t = failure; t != null; t = t.getCause()) {
      if (t instanceof SSLException) {
        return "Could not reach "
            + who
            + " (TLS handshake failed). Check the endpoint address and port, and the credentials.";
      }
      if (t instanceof UnknownHostException) {
        return "Could not find "
            + who
            + "'s server"
            + (t.getMessage() == null ? "" : " (" + t.getMessage() + ")")
            + ". Check the endpoint address.";
      }
      if (t instanceof ConnectException || t instanceof NoRouteToHostException) {
        return "Could not connect to "
            + who
            + " (connection refused). Check the endpoint address and port.";
      }
      if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) {
        return who + " did not answer in time. Try again, or check the endpoint.";
      }
      if (t.getClass().getSimpleName().equals("AuthenticationFailedException")) {
        return who + " refused the sign-in. Check the user name and password.";
      }
    }
    if (failure instanceof ProviderException && failure.getCause() == null) {
      // Our own wording, such as "Beem answered HTTP 401: …".
      return failure.getMessage();
    }
    String message = failure == null ? null : failure.getMessage();
    if (message != null && message.toLowerCase(Locale.ROOT).contains("answered http")) {
      return message;
    }
    return "Could not send through "
        + who
        + ". Check its settings; the server log has the details.";
  }
}
