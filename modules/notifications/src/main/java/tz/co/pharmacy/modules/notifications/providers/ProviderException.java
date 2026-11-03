package tz.co.pharmacy.modules.notifications.providers;

/** A provider refused or did not answer; the message is retried later. */
public class ProviderException extends RuntimeException {

  public ProviderException(String message) {
    super(message);
  }

  public ProviderException(String message, Throwable cause) {
    super(message, cause);
  }
}
