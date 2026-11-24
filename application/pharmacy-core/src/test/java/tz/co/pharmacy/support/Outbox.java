package tz.co.pharmacy.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads what the platform sent, as a test's mailbox: the invitation link in the email queued for an
 * address. Only the token's hash is stored, so the email is the one place the token is. An account
 * message is sealed until it is sent (AUTH-8): only sent ones are read.
 */
public final class Outbox {

  private static final Pattern TOKEN = Pattern.compile("token=([A-Za-z0-9_-]+)");

  private final String url;
  private final String user;
  private final String password;

  public Outbox(String url, String user, String password) {
    this.url = url;
    this.user = user;
    this.password = password;
  }

  /**
   * The token of the latest invitation emailed to {@code email} from pharmacy {@code tenantUid}.
   */
  public String invitationToken(String tenantUid, String email) {
    AtomicReference<String> token = new AtomicReference<>();
    Api.eventually(
        () -> {
          token.set(read(tenantUid, email));
          return token.get() != null;
        });
    return token.get();
  }

  /**
   * The body of the latest platform message (no pharmacy) of {@code templateKey} sent on {@code
   * channel} (EMAIL, SMS) to {@code address}, waiting for it; or null when {@code wait} is false
   * and there is none.
   */
  public String platformMessage(String templateKey, String channel, String address, boolean wait) {
    AtomicReference<String> body = new AtomicReference<>();
    if (!wait) {
      return latest(templateKey, channel, address);
    }
    Api.eventually(
        () -> {
          body.set(latest(templateKey, channel, address));
          return body.get() != null;
        });
    return body.get();
  }

  /** The {@code token=} of a link in a message body. */
  public static String token(String body) {
    Matcher matcher = TOKEN.matcher(body);
    return matcher.find() ? matcher.group(1) : null;
  }

  /** The 6-digit code in a message body. */
  public static String code(String body) {
    Matcher matcher = Pattern.compile("\\b(\\d{6})\\b").matcher(body);
    return matcher.find() ? matcher.group(1) : null;
  }

  private String latest(String templateKey, String channel, String address) {
    try (Connection connection = DriverManager.getConnection(url, user, password);
        PreparedStatement query =
            connection.prepareStatement(
                "select body from notifications.messages where template_key = ? and channel = ?"
                    + " and to_address = ? and not sealed order by id desc limit 1")) {
      query.setString(1, templateKey);
      query.setString(2, channel);
      query.setString(3, address);
      try (ResultSet rows = query.executeQuery()) {
        return rows.next() ? rows.getString(1) : null;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private String read(String tenantUid, String email) {
    try (Connection connection = DriverManager.getConnection(url, user, password)) {
      try (PreparedStatement tenant =
          connection.prepareStatement("select set_config('pharmacy.tenant_uid', ?, false)")) {
        tenant.setString(1, tenantUid);
        tenant.execute();
      }
      try (PreparedStatement query =
          connection.prepareStatement(
              "select body from notifications.messages where template_key = 'access.invitation'"
                  + " and channel = 'EMAIL' and to_address = ? and not sealed"
                  + " order by id desc limit 1")) {
        query.setString(1, email);
        try (ResultSet rows = query.executeQuery()) {
          if (!rows.next()) {
            return null;
          }
          Matcher matcher = TOKEN.matcher(rows.getString(1));
          return matcher.find() ? matcher.group(1) : null;
        }
      }
    } catch (SQLException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
