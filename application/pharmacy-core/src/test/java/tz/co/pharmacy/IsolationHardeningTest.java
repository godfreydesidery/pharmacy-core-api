package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.Api.Response;
import tz.co.pharmacy.support.IntegrationTest;
import tz.co.pharmacy.support.Outbox;

/**
 * Isolation between pharmacies and staff (adversarial review INT-6, ISO-6, ISO-7, ISO-9, ISO-10,
 * ISO-11): the users cap counts invitations and holds under parallel requests; a former employer
 * sees only what it kept; a pharmacy reads and writes only its own roles and memberships; a branch
 * named in a body follows the branch rules; people's data leaves only with {@code data.export}; and
 * the pharmacies a session entered are out of the application's reach.
 */
class IsolationHardeningTest extends IntegrationTest {

  private static final String ADMIN_PASSWORD = "Admin#2026x";
  private static final String STAFF_PASSWORD = "Staff#2026x";

  @Value("${spring.datasource.url}")
  String url;

  @Value("${pharmacy.database.owner-username}")
  String owner;

  @Value("${pharmacy.database.owner-password}")
  String ownerPassword;

  @Autowired DataSource dataSource;

  private Api root;

  private Api anonymous() {
    return api();
  }

  private Api root() {
    if (root == null) {
      Api anonymous = anonymous();
      root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    }
    return root;
  }

  /** A new pharmacy (on the Pro trial); its uid, with its admin's email. */
  private record Pharmacy(String uid, String code, String adminEmail, Api admin, String hq) {}

  private Pharmacy pharmacy(String prefix) {
    String code = prefix + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    String email = code.toLowerCase() + "@test.local";
    String tenant =
        root()
            .post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Isolation %s","legalName":"Isolation %s Ltd","country":"TZ",
                 "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
                 "headOffice":{"code":"HQ","name":"Head Office"},
                 "admin":{"name":"Admin","email":"%s","password":"%s"}}
                """
                    .formatted(code, code, code, email, ADMIN_PASSWORD))
            .expect(201)
            .string("$.data.uid");
    Api anonymous = anonymous();
    Api admin = anonymous.as(anonymous.signIn(email, ADMIN_PASSWORD)).in(tenant, null);
    String hq =
        admin.get("/api/v1/auth/me").expect(200).string("$.data.memberships[0].branches[0].uid");
    return new Pharmacy(tenant, code, email, admin, hq);
  }

  private static String roleUid(Api admin, String code) {
    List<String> roles =
        admin
            .get("/api/v1/roles")
            .expect(200)
            .json()
            .read("$.data[?(@.code == '" + code + "')].uid");
    return roles.getFirst();
  }

  private Response createLogin(Api admin, String roleUid, String branches) {
    String username = "staff." + UUID.randomUUID().toString().substring(0, 8);
    return admin.post(
        "/api/v1/users/managed",
        """
        {"username":"%s","name":"Staff %s","roleUid":"%s",%s}
        """
            .formatted(
                username,
                username,
                roleUid,
                branches == null
                    ? "\"allBranches\":true"
                    : "\"allBranches\":false,\"branchUids\":[\"" + branches + "\"]"));
  }

  /** A staff login of the pharmacy with its own password, signed in, in the pharmacy. */
  private record Login(Api api, String membershipUid) {}

  private Login login(Pharmacy pharmacy, String roleCode, String branch) {
    Response created =
        createLogin(pharmacy.admin(), roleUid(pharmacy.admin(), roleCode), branch).expect(201);
    String signIn = created.string("$.data.signIn");
    String temporary = created.string("$.data.temporaryPassword");
    Api anonymous = anonymous();
    anonymous
        .as(anonymous.signIn(signIn, temporary))
        .post(
            "/api/v1/auth/change-password",
            "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}"
                .formatted(temporary, STAFF_PASSWORD))
        .expect(200);
    return new Login(
        anonymous.as(anonymous.signIn(signIn, STAFF_PASSWORD)).in(pharmacy.uid(), null),
        created.string("$.data.member.membershipUid"));
  }

  private static Response invite(Api admin, String email, String roleUid) {
    return admin.post(
        "/api/v1/users/invitations",
        """
        {"name":"Invited","email":"%s","roleUid":"%s","allBranches":true}
        """
            .formatted(email, roleUid));
  }

  private static String email(String who) {
    return who + "." + UUID.randomUUID().toString().substring(0, 6) + "@test.local";
  }

  private static String product(Api admin, String name, String schedule) {
    return admin
        .post(
            "/api/v1/products",
            """
            {"name":"%s","genericName":"Generic","dosageForm":"TABLET","schedule":"%s",
             "sellingPrice":1000,"costPrice":500,"taxRatePercent":0,"storage":"ROOM_TEMPERATURE"}
            """
                .formatted(name, schedule))
        .expect(201)
        .string("$.data.uid");
  }

  private static void opening(Api till, String product, String batch, int quantity) {
    till.post(
            "/api/v1/stock/opening",
            """
            {"productUid":"%s","batchNumber":"%s","expiryDate":"%s","quantity":%d,"unitCost":500}
            """
                .formatted(product, batch, LocalDate.now().plusDays(200), quantity))
        .expect(201);
  }

  private static boolean active(Api admin, String membershipUid) {
    List<Boolean> found =
        admin
            .get("/api/v1/users?limit=100")
            .expect(200)
            .json()
            .read("$.data.items[?(@.membershipUid == '" + membershipUid + "')].active");
    return found.getFirst();
  }

  // ------------------------------------------------------------------- INT-6: the users cap

  @Test
  @DisplayName("INT-6 the users cap counts invitations, reactivations and admissions, in parallel")
  void usersCap() throws Exception {
    Pharmacy p = pharmacy("UC");
    Api admin = p.admin();
    Outbox outbox = new Outbox(url, owner, ownerPassword);
    // Starter allows 3 users; the administrator is the first.
    root()
        .put(
            "/api/v1/platform/tenants/" + p.uid() + "/subscription",
            "{\"planCode\":\"STARTER\",\"cycle\":\"MONTHLY\"}")
        .expect(200);
    String cashier = roleUid(admin, "CASHIER");

    // Pending invitations hold seats: two fit, the third does not, by any route.
    String a = email("a");
    invite(admin, a, cashier).expect(201);
    String b = invite(admin, email("b"), cashier).expect(201).string("$.data.uid");
    Response third = invite(admin, email("c"), cashier).expect(422);
    assertThat(third.string("$.reason")).isEqualTo("plan_limit_reached");
    assertThat(third.string("$.detail")).contains("allows 3 users", "3 are in use");
    assertThat(
            admin
                .post(
                    "/api/v1/users",
                    """
                    {"name":"C","email":"%s","roleUid":"%s","allBranches":true}
                    """
                        .formatted(email("c"), cashier))
                .expect(422)
                .string("$.reason"))
        .isEqualTo("plan_limit_reached");
    assertThat(createLogin(admin, cashier, null).expect(422).string("$.reason"))
        .isEqualTo("plan_limit_reached");
    // Sending an invitation again replaces the earlier one: still one seat.
    invite(admin, a, cashier).expect(201);
    List<Integer> used =
        admin
            .get("/api/v1/subscription")
            .expect(200)
            .json()
            .read("$.data.usage[?(@.limit == 'USERS')].used");
    assertThat(used).containsExactly(3);

    // A revoked invitation frees its seat.
    admin.delete("/api/v1/users/invitations/" + b).expect(200);
    String login =
        createLogin(admin, cashier, null).expect(201).string("$.data.member.membershipUid");

    // A missing "active" keeps a former member out; reactivating takes a seat.
    admin
        .put(
            "/api/v1/users/" + login,
            "{\"roleUid\":\"%s\",\"allBranches\":true,\"active\":false}".formatted(cashier))
        .expect(200);
    admin
        .put(
            "/api/v1/users/" + login,
            "{\"roleUid\":\"%s\",\"allBranches\":true,\"name\":\"Renamed\"}".formatted(cashier))
        .expect(200);
    assertThat(active(admin, login)).isFalse();
    String d = invite(admin, email("d"), cashier).expect(201).string("$.data.uid");
    Response back =
        admin.put(
            "/api/v1/users/" + login,
            "{\"roleUid\":\"%s\",\"allBranches\":true,\"active\":true}".formatted(cashier));
    assertThat(back.expect(422).string("$.reason")).isEqualTo("plan_limit_reached");
    assertThat(active(admin, login)).isFalse();

    // Parallel creates cannot pass the cap together: one seat left, one login made.
    admin.delete("/api/v1/users/invitations/" + d).expect(200);
    ExecutorService pool = Executors.newFixedThreadPool(5);
    List<Integer> statuses = new ArrayList<>();
    try {
      List<Callable<Integer>> creates = new ArrayList<>();
      for (int i = 0; i < 5; i++) {
        creates.add(() -> createLogin(admin, cashier, null).status());
      }
      for (Future<Integer> status : pool.invokeAll(creates)) {
        statuses.add(status.get());
      }
    } finally {
      pool.shutdown();
    }
    assertThat(statuses).containsOnly(201, 422);
    assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);

    // A root administrator is never held back, nor is the invitation they sent.
    String e = email("e");
    invite(root().in(p.uid(), null), e, cashier).expect(201);
    anonymous()
        .post(
            "/api/v1/auth/invitations/accept",
            "{\"token\":\"%s\",\"name\":\"E\",\"password\":\"Mine#2026xx\"}"
                .formatted(outbox.invitationToken(p.uid(), e)))
        .expect(201);
    // The members now fill the plan: an invitation sent earlier is not admitted over it.
    Response admitted =
        anonymous()
            .post(
                "/api/v1/auth/invitations/accept",
                "{\"token\":\"%s\",\"name\":\"A\",\"password\":\"Mine#2026xx\"}"
                    .formatted(outbox.invitationToken(p.uid(), a)));
    assertThat(admitted.expect(422).string("$.reason")).isEqualTo("plan_limit_reached");
    anonymous()
        .post("/api/v1/auth/sign-in", "{\"email\":\"" + a + "\",\"password\":\"Mine#2026xx\"}")
        .expect(401);
  }

  // ------------------------------------- ISO-6, ISO-7, ISO-11: one person in two pharmacies

  @Test
  @DisplayName("ISO-6/7/11 a person in two pharmacies: roles, rows, sessions and a former employer")
  void personInTwoPharmacies() throws Exception {
    Pharmacy p = pharmacy("IP");
    Pharmacy q = pharmacy("IQ");
    Outbox outbox = new Outbox(url, owner, ownerPassword);
    String product = product(p.admin(), "Paracetamol 500mg", "OTC");
    opening(p.admin().in(p.uid(), p.hq()), product, "PAR-1", 50);

    // Juma accepts the owner's invitation to P (as an administrator), then joins Q as a cashier.
    String juma = email("juma");
    invite(p.admin(), juma, roleUid(p.admin(), "ADMIN")).expect(201);
    anonymous()
        .post(
            "/api/v1/auth/invitations/accept",
            "{\"token\":\"%s\",\"name\":\"Juma Ali\",\"password\":\"%s\"}"
                .formatted(outbox.invitationToken(p.uid(), juma), STAFF_PASSWORD))
        .expect(201);
    invite(q.admin(), juma, roleUid(q.admin(), "CASHIER")).expect(201);
    Api anonymous = anonymous();
    Api session = anonymous.as(anonymous.signIn(juma, STAFF_PASSWORD));
    session
        .post(
            "/api/v1/auth/invitations/join",
            "{\"token\":\"%s\"}".formatted(outbox.invitationToken(q.uid(), juma)))
        .expect(200);
    String jumaUid = session.get("/api/v1/auth/me").expect(200).string("$.data.user.uid");

    // ISO-7: in P, the roles are P's only, though Juma can see his role in Q (to sign in there).
    List<String> qRoles = q.admin().get("/api/v1/roles").expect(200).json().read("$.data[*].uid");
    List<String> seenInP =
        session.in(p.uid(), null).get("/api/v1/roles").expect(200).json().read("$.data[*].uid");
    assertThat(seenInP).isNotEmpty().doesNotContainAnyElementsOf(qRoles);

    // ISO-7: the database lets P's transaction read Juma's role and membership in Q, not write.
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      as(c, p.uid(), jumaUid);
      long qRole =
          single(
              c,
              "select r.id from access.roles r join access.memberships m on m.role_id = r.id"
                  + " where m.user_uid = ?::uuid and m.tenant_uid = ?::uuid",
              jumaUid,
              q.uid());
      long qMembership =
          single(
              c,
              "select id from access.memberships where user_uid = ?::uuid and tenant_uid = ?::uuid",
              jumaUid,
              q.uid());
      long pCashier =
          single(
              c,
              "select id from access.roles where tenant_uid = ?::uuid and code = 'CASHIER'",
              p.uid());
      c.rollback();

      refusedByPolicy(
          c,
          p.uid(),
          jumaUid,
          "insert into access.role_permissions (role_id, permission) values ("
              + qRole
              + ", 'users.manage')");
      refusedByPolicy(
          c,
          p.uid(),
          jumaUid,
          "insert into access.membership_branches (membership_id, branch_uid) values ("
              + qMembership
              + ", gen_random_uuid())");
      assertThat(
              changed(
                  c,
                  p.uid(),
                  jumaUid,
                  "delete from access.role_permissions where role_id = " + qRole))
          .isZero();
      assertThat(
              changed(
                  c,
                  p.uid(),
                  jumaUid,
                  "delete from access.membership_branches where membership_id = " + qMembership))
          .isZero();
      assertThat(
              changed(
                  c,
                  p.uid(),
                  jumaUid,
                  "update access.memberships set tenant_uid = '"
                      + p.uid()
                      + "' where id = "
                      + qMembership))
          .isZero();
      assertThat(
              changed(
                  c,
                  p.uid(),
                  jumaUid,
                  "update access.roles set name = 'Taken' where id = " + qRole))
          .isZero();
      // P's own role is P's to change.
      assertThat(
              changed(
                  c,
                  p.uid(),
                  jumaUid,
                  "insert into access.role_permissions (role_id, permission) values ("
                      + pCashier
                      + ", 'reports.view') on conflict do nothing"))
          .isEqualTo(1);
    }

    // ISO-11: the pharmacies a session entered are out of the application role's reach, while
    // sign-ins and sign-outs are still recorded where the session worked.
    session.in(q.uid(), null).get("/api/v1/products").expect(200);
    try (Connection c = dataSource.getConnection()) {
      for (String privilege : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
        try (PreparedStatement s =
            c.prepareStatement(
                "select has_table_privilege(current_user, 'access.session_pharmacies', ?)")) {
          s.setString(1, privilege);
          try (ResultSet r = s.executeQuery()) {
            r.next();
            assertThat(r.getBoolean(1)).as(privilege).isFalse();
          }
        }
      }
      assertThatThrownBy(
              () -> c.createStatement().executeQuery("select * from access.session_pharmacies"))
          .hasMessageContaining("permission denied");
    }
    Api.eventually(
        () ->
            q.admin()
                .get("/api/v1/audit?action=SIGN_IN&limit=100")
                .expect(200)
                .body()
                .contains(juma));

    // Juma sells in P, so P's cashier report names him.
    session
        .in(p.uid(), p.hq())
        .post(
            "/api/v1/sales",
            """
            {"idempotencyKey":"%s","lines":[{"productUid":"%s","quantity":1}],
             "payments":[{"method":"CASH","amount":1000}]}
            """
                .formatted(UUID.randomUUID(), product))
        .expect(201);
    assertThat(cashierNames(p.admin())).contains("Juma Ali");

    // ISO-6: Juma leaves P, then changes his name. P keeps him as he was when he left.
    String inP = membershipOf(p.admin(), juma);
    p.admin()
        .put(
            "/api/v1/users/" + inP,
            "{\"roleUid\":\"%s\",\"allBranches\":true,\"active\":false}"
                .formatted(roleUid(p.admin(), "ADMIN")))
        .expect(200);
    session = anonymous.as(anonymous.signIn(juma, STAFF_PASSWORD));
    session.put("/api/v1/auth/profile", "{\"name\":\"Juma Mwinyi\"}").expect(200);
    assertThat(cashierNames(p.admin())).contains("Juma Ali").doesNotContain("Juma Mwinyi");

    // ISO-6: his account's lock is his current pharmacy's to see, not his former one's.
    for (int i = 0; i < 6; i++) {
      anonymous.post(
          "/api/v1/auth/sign-in", "{\"email\":\"" + juma + "\",\"password\":\"Wrong#2026x\"}");
    }
    String inQ = membershipOf(q.admin(), juma);
    assertThat(q.admin().get("/api/v1/users/locked").expect(200).body()).contains(inQ);
    assertThat(p.admin().get("/api/v1/users/locked").expect(200).body()).doesNotContain(inP);
    Response unlock = p.admin().post("/api/v1/users/" + inP + "/unlock", "{}").expect(422);
    assertThat(unlock.string("$.reason")).isEqualTo("not_managed");
    assertThat(unlock.string("$.detail")).doesNotContain("unlocks by itself");
  }

  private static List<String> cashierNames(Api admin) {
    return admin.get("/api/v1/reports/cashiers").expect(200).json().read("$.data[*].cashier");
  }

  private static String membershipOf(Api admin, String email) {
    List<String> found =
        admin
            .get("/api/v1/users?limit=100")
            .expect(200)
            .json()
            .read("$.data.items[?(@.email == '" + email + "')].membershipUid");
    return found.getFirst();
  }

  /** Starts the connection's transaction as the application does: this tenant and this user. */
  private static void as(Connection c, String tenant, String user) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement(
            "select set_config('pharmacy.tenant_uid', ?, true),"
                + " set_config('pharmacy.user_uid', ?, true)")) {
      s.setString(1, tenant);
      s.setString(2, user);
      s.executeQuery().close();
    }
  }

  private static long single(Connection c, String sql, String... args) throws SQLException {
    try (PreparedStatement s = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        s.setString(i + 1, args[i]);
      }
      try (ResultSet r = s.executeQuery()) {
        assertThat(r.next()).as(sql).isTrue();
        return r.getLong(1);
      }
    }
  }

  /** Rows the statement changed in a transaction of its own, rolled back. */
  private static int changed(Connection c, String tenant, String user, String sql)
      throws SQLException {
    try {
      as(c, tenant, user);
      try (PreparedStatement s = c.prepareStatement(sql)) {
        return s.executeUpdate();
      }
    } finally {
      c.rollback();
    }
  }

  private static void refusedByPolicy(Connection c, String tenant, String user, String sql) {
    assertThatThrownBy(() -> changed(c, tenant, user, sql))
        .as(sql)
        .hasMessageContaining("row-level security");
  }

  // ------------------------------------------------------------- ISO-9: branches in a body

  @Test
  @DisplayName("ISO-9 a branch price and a recall act only in branches the caller may work in")
  void branchesNamedInTheBody() {
    Pharmacy p = pharmacy("IB");
    Api admin = p.admin();
    String second =
        admin
            .post("/api/v1/branches", "{\"code\":\"B2\",\"name\":\"Second branch\"}")
            .expect(201)
            .string("$.data.uid");
    String product = product(admin, "Amoxicillin 250mg", "OTC");
    opening(admin.in(p.uid(), p.hq()), product, "LOT-SHARED", 10);
    opening(admin.in(p.uid(), second), product, "LOT-SHARED", 10);
    opening(admin.in(p.uid(), p.hq()), product, "LOT-HQ", 10);
    Api manager = login(p, "BRANCH_MANAGER", p.hq()).api();

    String price = "{\"productUid\":\"%s\",\"branchUid\":\"%s\",\"price\":1200}";
    Response elsewhere = manager.put("/api/v1/branch-prices", price.formatted(product, second));
    assertThat(elsewhere.expect(403).string("$.reason")).isEqualTo("branch_not_allowed");
    manager.put("/api/v1/branch-prices", price.formatted(product, p.hq())).expect(200);
    admin.put("/api/v1/branch-prices", price.formatted(product, second)).expect(200);

    List<Map<String, Object>> batches =
        admin
            .get("/api/v1/recalls/products/" + product + "/batches")
            .expect(200)
            .json()
            .read("$.data[*]");
    String shared = batchUid(batches, "LOT-SHARED");
    String own = batchUid(batches, "LOT-HQ");
    String reason = "{\"reason\":\"Manufacturer recall\"}";

    // Held at the manager's branch only: theirs to quarantine and release.
    manager.post("/api/v1/recalls/batches/" + own + "/quarantine", reason).expect(200);
    manager.post("/api/v1/recalls/batches/" + own + "/release", null).expect(200);
    // Held at another branch too: the quarantine would act there, so it is refused.
    Response shut = manager.post("/api/v1/recalls/batches/" + shared + "/quarantine", reason);
    assertThat(shut.expect(403).string("$.reason")).isEqualTo("branch_not_allowed");
    admin.post("/api/v1/recalls/batches/" + shared + "/quarantine", reason).expect(200);
    Response open = manager.post("/api/v1/recalls/batches/" + shared + "/release", null);
    assertThat(open.expect(403).string("$.reason")).isEqualTo("branch_not_allowed");
    assertThat(
            admin
                .get("/api/v1/recalls/batches/" + shared)
                .expect(200)
                .read("$.data.batch.quarantined", Boolean.class))
        .isTrue();
  }

  private static String batchUid(List<Map<String, Object>> batches, String number) {
    return batches.stream()
        .filter(batch -> number.equals(batch.get("batchNumber")))
        .map(batch -> (String) batch.get("uid"))
        .findFirst()
        .orElseThrow();
  }

  // -------------------------------------------------------- ISO-10: people's data and reads

  @Test
  @DisplayName("ISO-10 people's data leaves only with data.export; every read needs a permission")
  void peoplesDataInBulk() {
    Pharmacy p = pharmacy("IX");
    Api admin = p.admin();
    String customer =
        admin
            .post("/api/v1/customers", "{\"name\":\"Mama Neema\",\"phone\":\"+255700000077\"}")
            .expect(201)
            .string("$.data.uid");
    String morphine = product(admin, "Morphine 10mg", "CONTROLLED");
    opening(admin.in(p.uid(), p.hq()), morphine, "MOR-1", 20);
    String register = "/api/v1/controlled-drugs/" + morphine + "/register/export?format=csv";

    // The permission is in the catalogue, and in the presets of those who run the pharmacy.
    assertThat(admin.get("/api/v1/permissions").expect(200).body()).contains("data.export");
    List<List<String>> adminPermissions =
        admin
            .get("/api/v1/roles")
            .expect(200)
            .json()
            .read("$.data[?(@.code == 'ADMIN')].permissions");
    assertThat(adminPermissions.getFirst()).contains("data.export");
    // A new pharmacy's built-in roles are the presets: no notice to apply them.
    assertThat(
            admin
                .get("/api/v1/roles/recommendations")
                .expect(200)
                .read("$.data.showNotice", Boolean.class))
        .isFalse();

    Login cashier = login(p, "CASHIER", null);
    Login pharmacist = login(p, "PHARMACIST", null);
    Login storeKeeper = login(p, "STORE_KEEPER", null);
    Login accountant = login(p, "ACCOUNTANT", null);
    Login manager = login(p, "BRANCH_MANAGER", null);

    // Customers with phones and insurance numbers: those who run the pharmacy only.
    for (Login denied : List.of(cashier, pharmacist, storeKeeper)) {
      Response refused = denied.api().get("/api/v1/customers/export?format=csv").expect(403);
      assertThat(refused.string("$.reason")).isEqualTo("forbidden");
      assertThat(refused.string("$.detail")).contains("data.export");
      // The list on screen stays theirs.
      denied.api().get("/api/v1/customers").expect(200);
    }
    for (Api allowed : List.of(admin, accountant.api(), manager.api())) {
      assertThat(allowed.get("/api/v1/customers/export?format=csv").expect(200).body())
          .contains("Mama Neema");
    }

    // The controlled-drugs register names patients and prescriptions: the same rule. The
    // register on screen is still the pharmacist's.
    Api pharmacistAtHq = pharmacist.api().in(p.uid(), p.hq());
    assertThat(pharmacistAtHq.get(register).expect(403).string("$.detail")).contains("data.export");
    pharmacistAtHq.get("/api/v1/controlled-drugs/" + morphine + "/register").expect(200);
    admin.in(p.uid(), p.hq()).get(register).expect(200);
    // The accountant may export people's data, but not a register they may not view.
    assertThat(accountant.api().in(p.uid(), p.hq()).get(register).expect(403).string("$.detail"))
        .contains("inventory.view");

    // Other exports keep their permissions.
    cashier.api().in(p.uid(), p.hq()).get("/api/v1/sales/export?format=csv").expect(200);
    pharmacistAtHq.get("/api/v1/stock/export?format=csv").expect(200);

    // A customer's account: the till (sales.sell) and the accounts page (finance.view) only.
    cashier.api().get("/api/v1/receivables/accounts/" + customer).expect(200);
    accountant.api().get("/api/v1/receivables/accounts/" + customer).expect(200);
    assertThat(
            storeKeeper
                .api()
                .get("/api/v1/receivables/accounts/" + customer)
                .expect(403)
                .string("$.reason"))
        .isEqualTo("forbidden");

    // A customer's insurance membership: partners.view, as the list of members.
    cashier.api().get("/api/v1/insurance/members/current?customerUid=" + customer).expect(200);
    String viewerRole =
        admin
            .post(
                "/api/v1/roles",
                "{\"code\":\"VIEWER\",\"name\":\"Viewer\",\"permissions\":[\"dashboard.view\"]}")
            .expect(201)
            .string("$.data.uid");
    Response viewerLogin = createLogin(admin, viewerRole, null).expect(201);
    Api anonymous = anonymous();
    String signIn = viewerLogin.string("$.data.signIn");
    String temporary = viewerLogin.string("$.data.temporaryPassword");
    anonymous
        .as(anonymous.signIn(signIn, temporary))
        .post(
            "/api/v1/auth/change-password",
            "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}"
                .formatted(temporary, STAFF_PASSWORD))
        .expect(200);
    Api viewer = anonymous.as(anonymous.signIn(signIn, STAFF_PASSWORD)).in(p.uid(), null);
    viewer.get("/api/v1/insurance/members/current?customerUid=" + customer).expect(403);
    viewer.get("/api/v1/receivables/accounts/" + customer).expect(403);
  }
}
