package tz.co.pharmacy.delivery.api.v1.platform;

import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;

/**
 * The page a problem's {@code type} names (STD-14). Every refusal's type is the relative reference
 * {@code /problems/<reason>}, resolved against the API's own address, so it always points at a host
 * the API's operator controls; dereferenced, it says in plain words what the type is (RFC 9457
 * section 3.1.1: a type URI should lead to documentation for people). Clients read the problem's
 * {@code reason}, never this page.
 */
@RestController
@RequestMapping("/problems")
public class ProblemTypesHandler {

  private static final Pattern REASON = Pattern.compile("[a-z][a-z0-9_]{0,79}");

  @GetMapping("/{reason}")
  @Access(scope = AccessScope.PUBLIC)
  public ResponseEntity<String> describe(@PathVariable String reason) {
    if (!REASON.matcher(reason).matches()) {
      return ResponseEntity.notFound().build();
    }
    String text =
        """
        Pharmacy Core API problem type: %1$s

        A refusal of the Pharmacy Core API (an RFC 9457 problem, application/problem+json) whose
        "reason" member is "%1$s". Its "detail" says what went wrong in words for people, "field"
        names the input at fault when there is one, and "correlationId" finds the request in the
        API's logs. Clients act on "reason", which never changes for a type.
        """
            .formatted(reason);
    return ResponseEntity.ok()
        .contentType(new MediaType(MediaType.TEXT_PLAIN, java.nio.charset.StandardCharsets.UTF_8))
        .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(1)).cachePublic())
        .header("X-Content-Type-Options", "nosniff")
        .body(text);
  }
}
