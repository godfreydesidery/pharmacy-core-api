package tz.co.pharmacy.delivery.api;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** The success envelope of every response: whether it succeeded, a code, a message and the data. */
public record ApiResponse<T>(
    boolean success, String code, String message, Instant timestamp, T data) {

  public static <T> ApiResponse<T> ok(T data, String message) {
    return new ApiResponse<>(true, "OK", message, now(), data);
  }

  public static ApiResponse<Void> ok(String message) {
    return new ApiResponse<>(true, "OK", message, now(), null);
  }

  public static <T> ApiResponse<T> created(T data, String message) {
    return new ApiResponse<>(true, "CREATED", message, now(), data);
  }

  private static Instant now() {
    return Instant.now().truncatedTo(ChronoUnit.MILLIS);
  }
}
