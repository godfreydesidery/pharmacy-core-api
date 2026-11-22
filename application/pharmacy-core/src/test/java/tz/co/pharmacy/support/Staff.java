package tz.co.pharmacy.support;

import java.util.List;
import java.util.UUID;
import tz.co.pharmacy.support.Api.Response;

/**
 * Staff for a test. Adding a person by email only invites them, so a test that needs a colleague
 * who signs in with a password of its own makes a managed login (username@CODE) and sets that
 * password, as the person would at first sign-in.
 */
public final class Staff {

  private Staff() {}

  /** A login's sign-in (username@code) and its membership in the pharmacy. */
  public record Login(String signIn, String membershipUid) {}

  /**
   * A managed login of {@code admin}'s pharmacy with role {@code roleCode} and {@code password}.
   */
  public static Login login(
      Api admin, Api anonymous, String name, String roleCode, String password) {
    List<String> roles =
        admin
            .get("/api/v1/roles")
            .expect(200)
            .json()
            .read("$.data[?(@.code == '" + roleCode + "')].uid");
    String username = "staff." + UUID.randomUUID().toString().substring(0, 8);
    Response created =
        admin
            .post(
                "/api/v1/users/managed",
                """
                {"username":"%s","name":"%s","roleUid":"%s","allBranches":true}
                """
                    .formatted(username, name, roles.getFirst()))
            .expect(201);
    String signIn = created.string("$.data.signIn");
    String temporary = created.string("$.data.temporaryPassword");
    anonymous
        .as(anonymous.signIn(signIn, temporary))
        .post(
            "/api/v1/auth/change-password",
            "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(temporary, password))
        .expect(200);
    return new Login(signIn, created.string("$.data.member.membershipUid"));
  }
}
