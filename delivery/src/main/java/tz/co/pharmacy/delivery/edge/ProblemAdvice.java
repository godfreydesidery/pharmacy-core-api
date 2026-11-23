package tz.co.pharmacy.delivery.edge;

import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Refusal;

/** Turns every failure of a handler into an RFC 9457 problem with a stable {@code reason}. */
@RestControllerAdvice
public class ProblemAdvice {

  private static final Logger log = LoggerFactory.getLogger(ProblemAdvice.class);

  @ExceptionHandler(Refusal.class)
  ResponseEntity<ProblemDetail> refusal(Refusal refusal) {
    ResponseEntity<ProblemDetail> problem =
        problem(refusal.kind().status(), refusal.reason(), refusal.getMessage(), refusal.field());
    if (refusal.retryAfterSeconds() == null) {
      return problem;
    }
    problem.getBody().setProperty("retryAfterSeconds", refusal.retryAfterSeconds());
    return ResponseEntity.status(problem.getStatusCode())
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .header("Retry-After", String.valueOf(refusal.retryAfterSeconds()))
        .body(problem.getBody());
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ProblemDetail> unreadable(HttpMessageNotReadableException exception) {
    return problem(
        400, "malformed_request", "The request body is not valid JSON for this route.", null);
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<ProblemDetail> mismatch(MethodArgumentTypeMismatchException exception) {
    return problem(
        422,
        "invalid_" + exception.getName(),
        exception.getName() + " is not a valid value.",
        exception.getName());
  }

  @ExceptionHandler({
    MissingServletRequestParameterException.class,
    MissingRequestHeaderException.class
  })
  ResponseEntity<ProblemDetail> missing(Exception exception) {
    return problem(422, "missing_input", exception.getMessage(), null);
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<ProblemDetail> noRoute(NoResourceFoundException exception) {
    return problem(404, "route_not_found", "No such route.", null);
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<ProblemDetail> method(HttpRequestMethodNotSupportedException exception) {
    return problem(405, "method_not_allowed", exception.getMessage(), null);
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ResponseEntity<ProblemDetail> mediaType(HttpMediaTypeNotSupportedException exception) {
    return problem(415, "unsupported_media_type", "Send application/json.", null);
  }

  @ExceptionHandler(OptimisticLockingFailureException.class)
  ResponseEntity<ProblemDetail> stale(OptimisticLockingFailureException exception) {
    return problem(
        409, "stale_version", "Someone changed this record meanwhile; reload and retry.", null);
  }

  /** Seconds a client waits before retrying a request that lost a lock to another one. */
  static final long BUSY_RETRY_SECONDS = 1;

  /**
   * Another request held what this one needed: a lock wait that timed out, a deadlock the database
   * broke by refusing this transaction (SQLState 40P01), or a serialization failure (40001).
   * Nothing was written; the same request sent again succeeds. 409 busy_try_again, Retry-After.
   */
  @ExceptionHandler(PessimisticLockingFailureException.class)
  ResponseEntity<ProblemDetail> busy(PessimisticLockingFailureException exception) {
    log.info("Lost a lock to another request: {}", exception.getMostSpecificCause().getMessage());
    return busy();
  }

  private static ResponseEntity<ProblemDetail> busy() {
    ResponseEntity<ProblemDetail> problem =
        problem(
            409,
            "busy_try_again",
            "Another till was using the same stock or account; send it again.",
            null);
    problem.getBody().setProperty("retryAfterSeconds", BUSY_RETRY_SECONDS);
    return ResponseEntity.status(409)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .header("Retry-After", String.valueOf(BUSY_RETRY_SECONDS))
        .body(problem.getBody());
  }

  /** Whether a failure is, somewhere in its causes, a lock lost to another transaction. */
  static boolean lostLock(Throwable failure) {
    for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
      if (t instanceof PessimisticLockingFailureException) {
        return true;
      }
      if (t instanceof java.sql.SQLException sql) {
        String state = sql.getSQLState();
        if ("40P01".equals(state) || "40001".equals(state) || "55P03".equals(state)) {
          return true;
        }
      }
    }
    return false;
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<ProblemDetail> integrity(DataIntegrityViolationException exception) {
    log.warn("Integrity violation: {}", exception.getMostSpecificCause().getMessage());
    return problem(409, "conflict", "This conflicts with an existing record.", null);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ProblemDetail> unexpected(Exception exception) {
    if (lostLock(exception)) {
      // A deadlock or lock timeout surfaced wrapped (at commit, through JPA): still a retry.
      log.info("Lost a lock to another request: {}", exception.toString());
      return busy();
    }
    log.error("Unexpected failure", exception);
    return problem(500, "internal_error", "Something went wrong; it has been logged.", null);
  }

  static ResponseEntity<ProblemDetail> problem(
      int status, String reason, String detail, String field) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(status), detail);
    problem.setType(URI.create(Problems.TYPE_PREFIX + reason));
    problem.setTitle(Problems.title(status));
    problem.setProperty("reason", reason);
    if (field != null) {
      problem.setProperty("field", field);
    }
    problem.setProperty("correlationId", ExecutionContext.correlationId().orElse(null));
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(problem);
  }
}
