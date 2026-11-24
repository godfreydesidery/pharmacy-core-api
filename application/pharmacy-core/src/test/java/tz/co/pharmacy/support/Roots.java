package tz.co.pharmacy.support;

import java.util.List;

/**
 * The second root administrator that lifting a legal hold and erasing a pharmacy need (FRD-18): one
 * root administrator asks, this one approves with their password.
 */
public final class Roots {

  public static final String SECOND_EMAIL = "second-root@test.local";
  public static final String SECOND_PASSWORD = "Root2#Test2026-strong";

  private Roots() {}

  /** The second root administrator's session, created or reactivated as needed. */
  public static Api second(Api root, Api anonymous) {
    List<String> uids =
        root.get("/api/v1/platform/root-admins")
            .expect(200)
            .json()
            .read("$.data[?(@.email == '" + SECOND_EMAIL + "')].uid");
    if (uids.isEmpty()) {
      root.post(
              "/api/v1/platform/root-admins",
              "{\"name\":\"Second Root\",\"email\":\"%s\",\"password\":\"%s\"}"
                  .formatted(SECOND_EMAIL, SECOND_PASSWORD))
          .expect(201);
    } else {
      root.post("/api/v1/platform/root-admins/" + uids.getFirst() + "/reactivate", null);
    }
    return anonymous.as(anonymous.signIn(SECOND_EMAIL, SECOND_PASSWORD));
  }

  /**
   * {@code root} asks for the action and the second root administrator approves it; the answer to
   * the approval (200 when the action ran).
   */
  public static Api.Response twoRoots(
      Api root, Api anonymous, String action, String tenantUid, String confirmCode) {
    Api second = second(root, anonymous);
    String uid =
        root.post(
                "/api/v1/platform/approvals",
                "{\"action\":\"%s\",\"tenantUid\":\"%s\",\"reason\":\"Test case\"%s}"
                    .formatted(
                        action,
                        tenantUid,
                        confirmCode == null ? "" : ",\"confirmCode\":\"" + confirmCode + "\""))
            .expect(201)
            .string("$.data.uid");
    return second.post(
        "/api/v1/platform/approvals/" + uid + "/approve",
        "{\"password\":\"%s\"}".formatted(SECOND_PASSWORD));
  }
}
