package tz.co.pharmacy.delivery.edge;

import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * Conditional changes (RFC 9110 §13.1.1 If-Match, RFC 6585 §3 428). A resource that people edit (a
 * product, customer, supplier, role, branch, price list, the settings) answers its GET with {@code
 * ETag: "<version>"}, the row's optimistic-locking version ({@link
 * tz.co.pharmacy.platform.kernel.Versioned}). A change that sends {@code If-Match} with that tag
 * goes ahead only while the row still has that version:
 *
 * <ul>
 *   <li>a tag that is not the current one: 412 {@code precondition_failed}, with {@code
 *       currentVersion}, "someone else changed this; reload it";
 *   <li>{@code If-Match: *} matches any version of an existing resource;
 *   <li>a weak tag ({@code W/"7"}) is accepted as {@code "7"}: proxies that compress a response
 *       weaken its tag, and the version names the row's state whatever the encoding;
 *   <li>no header: allowed, as before (clients that predate it), unless {@code
 *       pharmacy.http.require-if-match} is true: then 428 {@code precondition_required}.
 * </ul>
 *
 * The handler compares inside its transaction, with the row it is about to change, so a change that
 * crosses another one after the comparison still fails at commit (409 stale_version).
 */
@Component
public class Preconditions {

  public static final String IF_MATCH = "If-Match";

  private final boolean required;

  public Preconditions(@Value("${pharmacy.http.require-if-match:false}") boolean required) {
    this.required = required;
  }

  /**
   * Refuses the change unless {@code ifMatch} (the request's header, or null) names {@code
   * current}'s version. {@code current} is read only when the header is present.
   */
  public void ifMatch(String ifMatch, LongSupplier current) {
    if (ifMatch == null || ifMatch.isBlank()) {
      if (required) {
        throw Refusal.preconditionRequired(
            "Send If-Match with the ETag of the record as you read it, so a change made meanwhile"
                + " by someone else is not overwritten.");
      }
      return;
    }
    long version = current.getAsLong();
    if (!matches(ifMatch, version)) {
      throw Refusal.preconditionFailed(
              "Someone else changed this record since you read it; reload it to see their"
                  + " change.")
          .with("currentVersion", version);
    }
  }

  /** The entity tag of a version: a strong tag, its decimal digits quoted. */
  public static String tag(long version) {
    return "\"" + version + "\"";
  }

  /** Whether an If-Match list (RFC 9110 §8.8.3, §13.1.1) names {@code version} or is "*". */
  static boolean matches(String header, long version) {
    String wanted = String.valueOf(version);
    for (String part : header.split(",")) {
      String tag = part.trim();
      if (tag.equals("*")) {
        return true;
      }
      if (tag.startsWith("W/")) {
        tag = tag.substring(2);
      }
      if (tag.length() >= 2 && tag.startsWith("\"") && tag.endsWith("\"")) {
        if (tag.substring(1, tag.length() - 1).equals(wanted)) {
          return true;
        }
      }
    }
    return false;
  }
}
