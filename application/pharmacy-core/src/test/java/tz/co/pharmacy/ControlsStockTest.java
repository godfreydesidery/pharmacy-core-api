package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;
import static tz.co.pharmacy.support.Api.eventually;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.Api.Response;
import tz.co.pharmacy.support.IntegrationTest;
import tz.co.pharmacy.support.Staff;

/**
 * The owner's stock and catalogue controls of 23 Nov 2026 (Batch 4; FRD-2, 5, 6, 11, 12, 15):
 * write-offs say why and a manager approves controlled medicines always and other stock above the
 * pharmacy's limit; stock-takes are counted blind; opening stock belongs to setting up; repacks
 * follow their recipe and never lower a medicine's class; a class change needs a manager and the
 * register keeps every product ever controlled; a transfer is received by someone else, line by
 * line, and what never arrived is the sender's loss.
 */
class ControlsStockTest extends IntegrationTest {

  private static final String ADMIN_PASSWORD = "Admin#2026x";
  private static final String MANAGER_PASSWORD = "Manager#2026x";

  @Autowired DataSource dataSource;

  private Api anonymous;
  private Api admin;
  private Api adminHq;
  private Api manager;
  private String tenant;
  private String hq;
  private String adminEmail;
  private String managerLogin;

  @BeforeEach
  void pharmacy() {
    anonymous = api();
    Api root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "CS" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    adminEmail = code.toLowerCase() + "@test.local";
    tenant =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Controls %s","legalName":"Controls %s Ltd","country":"TZ",
                 "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
                 "headOffice":{"code":"HQ","name":"Head Office"},
                 "admin":{"name":"Neema Owner","email":"%s","password":"%s"}}
                """
                    .formatted(code, code, code, adminEmail, ADMIN_PASSWORD))
            .expect(201)
            .string("$.data.uid");
    admin = anonymous.as(anonymous.signIn(adminEmail, ADMIN_PASSWORD)).in(tenant, null);
    hq = admin.get("/api/v1/auth/me").expect(200).string("$.data.memberships[0].branches[0].uid");
    adminHq = admin.in(tenant, hq);
    managerLogin =
        Staff.login(admin, anonymous, "Juma Manager", "BRANCH_MANAGER", MANAGER_PASSWORD).signIn();
    manager = anonymous.as(anonymous.signIn(managerLogin, MANAGER_PASSWORD)).in(tenant, hq);
  }

  // ------------------------------------------------------------------ FRD-5 write-offs

  @Test
  @DisplayName(
      "FRD-5: a write-off needs a note, a manager above the limit, and always for a controlled"
          + " medicine")
  void writeOffs() {
    String paracetamol = product("Paracetamol 500mg", "OTC", 1_000);
    String morphine = product("Morphine 10mg", "CONTROLLED", 100);
    String para = opening(manager, paracetamol, "PCM-1", 200, 1_000).string("$.data.batchUid");
    String mor = opening(manager, morphine, "MOR-1", 20, 100).string("$.data.batchUid");

    // A reduction without a note is refused; with one, below the limit, it goes through.
    assertThat(
            manager
                .post("/api/v1/stock/adjustments", adjustment(paracetamol, para, -1, null))
                .expect(422)
                .string("$.reason"))
        .isEqualTo("note_required");
    manager
        .post("/api/v1/stock/adjustments", adjustment(paracetamol, para, -1, "Dropped"))
        .expect(201);

    // 60 at 1,000 is above the 50,000 limit: a manager approves, at cost.
    String big = adjustment(paracetamol, para, -60, "Water damage");
    Response refused = manager.post("/api/v1/stock/adjustments", big).expect(403);
    assertThat(refused.string("$.reason")).isEqualTo("approval_required");
    assertThat(refused.string("$.action")).isEqualTo("STOCK_WRITE_OFF");
    assertThat(refused.read("$.amount", Double.class)).isEqualTo(60_000.0);
    manager
        .post("/api/v1/stock/adjustments", big, "X-Approval", approve("STOCK_WRITE_OFF", 60_000))
        .expect(201);

    // A controlled medicine always: one tablet of 100.
    String one = adjustment(morphine, mor, -1, "Broken ampoule");
    Response controlled = manager.post("/api/v1/stock/adjustments", one).expect(403);
    assertThat(controlled.string("$.action")).isEqualTo("CONTROLLED_WRITE_OFF");
    assertThat(controlled.read("$.amount", Double.class)).isEqualTo(100.0);
    manager
        .post("/api/v1/stock/adjustments", one, "X-Approval", approve("CONTROLLED_WRITE_OFF", 100))
        .expect(201);
    // Stock found (an increase) is no write-off.
    manager.post("/api/v1/stock/adjustments", adjustment(morphine, mor, 1, null)).expect(201);

    // A destruction of a controlled medicine: note, then the manager who approves is the witness.
    String destruction =
        """
        {"method":"Incineration","witnessName":%s,"note":%s,
         "lines":[{"batchUid":"%s","quantity":2,"reason":"DAMAGED"}]}
        """;
    assertThat(
            manager
                .post("/api/v1/destructions", destruction.formatted("\"Asha\"", "null", mor))
                .expect(422)
                .string("$.reason"))
        .isEqualTo("note_required");
    String body = destruction.formatted("\"Asha (TMDA)\"", "\"Crushed vials\"", mor);
    Response unapproved = manager.post("/api/v1/destructions", body).expect(403);
    assertThat(unapproved.string("$.action")).isEqualTo("CONTROLLED_WRITE_OFF");
    assertThat(unapproved.read("$.amount", Double.class)).isEqualTo(200.0);
    Response destroyed =
        manager
            .post("/api/v1/destructions", body, "X-Approval", approve("CONTROLLED_WRITE_OFF", 200))
            .expect(201);
    assertThat(destroyed.string("$.data.witnessName")).isEqualTo("Neema Owner");
    assertThat(destroyed.read("$.data.witnessApproved", Boolean.class)).isTrue();
    assertThat(destroyed.string("$.data.typedWitnessName")).isEqualTo("Asha (TMDA)");

    // A small destruction of ordinary stock needs no manager; its witness is the one typed.
    Response small =
        manager
            .post(
                "/api/v1/destructions",
                destruction.formatted("\"Asha\"", "\"Torn boxes\"", para).replace("2,", "3,"))
            .expect(201);
    assertThat(small.string("$.data.witnessName")).isEqualTo("Asha");
    assertThat(small.read("$.data.witnessApproved", Boolean.class)).isFalse();
  }

  // ------------------------------------------------------------------ FRD-12 stock-takes

  @Test
  @DisplayName(
      "FRD-12: a count is blind until reviewed or posted; its loss needs a manager above the limit"
          + " and always for a controlled medicine")
  void blindCounts() {
    String amoxil = product("Amoxicillin 250mg", "PRESCRIPTION", 1_000);
    String pethidine = product("Pethidine 50mg", "CONTROLLED", 500);
    opening(manager, amoxil, "AMX-1", 100, 1_000);
    opening(manager, pethidine, "PET-1", 10, 500);

    Response sheet = manager.post("/api/v1/stock-counts", "{}").expect(201);
    String count = sheet.string("$.data.uid");
    assertThat(sheet.read("$.data.blind", Boolean.class)).isTrue();
    assertThat(sheet.json().<List<Object>>read("$.data.items[*].expected"))
        .allMatch(expected -> expected == null);
    String amxLine = line(sheet, "AMX-1");

    // Counted 40 short (40,000 at cost): the counter still sees no expected or variance.
    Response recorded =
        manager
            .put(
                "/api/v1/stock-counts/" + count + "/counts",
                "[{\"lineUid\":\"%s\",\"counted\":60}]".formatted(amxLine))
            .expect(200);
    assertThat(recorded.read("$.data.items[?(@.batchNumber == 'AMX-1')].counted", List.class))
        .containsExactly(60);
    assertThat(recorded.read("$.data.items[?(@.batchNumber == 'AMX-1')].variance", List.class))
        .containsExactly((Object) null);
    assertThat(recorded.string("$.data.varianceValue")).isNull();
    Response got = manager.get("/api/v1/stock-counts/" + count).expect(200);
    assertThat(got.read("$.data.items[?(@.batchNumber == 'AMX-1')].expected", List.class))
        .containsExactly((Object) null);
    assertThat(
            manager.get("/api/v1/stock-counts").expect(200).string("$.data.items[0].varianceValue"))
        .isNull();

    // The reviewer sees the expected quantities and the loss.
    Response review = manager.get("/api/v1/stock-counts/" + count + "/review").expect(200);
    assertThat(review.read("$.data.blind", Boolean.class)).isFalse();
    assertThat(review.read("$.data.items[?(@.batchNumber == 'AMX-1')].expected", List.class))
        .containsExactly(100);
    assertThat(review.read("$.data.lossValue", Double.class)).isEqualTo(40_000.0);

    // Below the limit: posted without a manager, and the posted count shows everything.
    Response posted = manager.post("/api/v1/stock-counts/" + count + "/post", "{}").expect(200);
    assertThat(posted.string("$.data.status")).isEqualTo("POSTED");
    assertThat(posted.read("$.data.items[?(@.batchNumber == 'AMX-1')].variance", List.class))
        .containsExactly(-40);
    assertThat(
            manager
                .get("/api/v1/stock-counts/" + count)
                .expect(200)
                .read("$.data.items[?(@.batchNumber == 'AMX-1')].expected", List.class))
        .containsExactly(100);

    // Next count: 55 more short is above the limit; one pethidine short is controlled.
    Response second = manager.post("/api/v1/stock-counts", "{}").expect(201);
    String next = second.string("$.data.uid");
    manager
        .put(
            "/api/v1/stock-counts/" + next + "/counts",
            "[{\"lineUid\":\"%s\",\"counted\":5}]".formatted(line(second, "AMX-1")))
        .expect(200);
    Response above = manager.post("/api/v1/stock-counts/" + next + "/post", "{}").expect(403);
    assertThat(above.string("$.action")).isEqualTo("STOCK_WRITE_OFF");
    assertThat(above.read("$.amount", Double.class)).isEqualTo(55_000.0);
    manager
        .put(
            "/api/v1/stock-counts/" + next + "/counts",
            "[{\"lineUid\":\"%s\",\"counted\":9}]".formatted(line(second, "PET-1")))
        .expect(200);
    Response controlled = manager.post("/api/v1/stock-counts/" + next + "/post", "{}").expect(403);
    assertThat(controlled.string("$.action")).isEqualTo("CONTROLLED_WRITE_OFF");
    assertThat(controlled.read("$.amount", Double.class)).isEqualTo(55_500.0);
    manager
        .post(
            "/api/v1/stock-counts/" + next + "/post",
            "{}",
            "X-Approval",
            approve("CONTROLLED_WRITE_OFF", 55_500))
        .expect(200);
    assertThat(onHand(manager, amoxil)).isEqualTo(5);
    assertThat(onHand(manager, pethidine)).isEqualTo(9);
  }

  // ------------------------------------------------------------------ FRD-11 opening stock

  @Test
  @DisplayName(
      "FRD-11: opening stock is free until the branch trades, then needs a manager; it never"
          + " re-costs a batch")
  void openingStockOnlyDuringSetUp() {
    String ors = product("ORS sachet", "OTC", 300);
    opening(manager, ors, "ORS-1", 10, 300);
    // Into the same batch at another cost: refused, not averaged in.
    Response differs =
        manager.post("/api/v1/stock/opening", openingBody(ors, "ORS-1", 5, 350)).expect(422);
    assertThat(differs.string("$.reason")).isEqualTo("batch_cost_differs");
    assertThat(differs.read("$.batchUnitCost", Double.class)).isEqualTo(300.0);
    opening(manager, ors, "ORS-1", 5, 300);

    // The branch receives goods: from now on opening stock needs a manager.
    String supplier =
        admin
            .post("/api/v1/suppliers", "{\"name\":\"Lake Drugs\",\"paymentTerms\":\"COD\"}")
            .expect(201)
            .string("$.data.uid");
    manager
        .post(
            "/api/v1/goods-receipts",
            """
            {"supplierUid":"%s","supplierInvoiceNumber":"INV-1","lines":[
              {"productUid":"%s","batchNumber":"ORS-2","expiryDate":"%s","quantity":4,"unitCost":300}]}
            """
                .formatted(supplier, ors, LocalDate.now().plusYears(1)))
        .expect(201);
    String late = openingBody(ors, "ORS-3", 6, 300);
    Response refused = manager.post("/api/v1/stock/opening", late).expect(403);
    assertThat(refused.string("$.reason")).isEqualTo("approval_required");
    assertThat(refused.string("$.action")).isEqualTo("LATE_OPENING_STOCK");
    manager
        .post("/api/v1/stock/opening", late, "X-Approval", approve("LATE_OPENING_STOCK", 1_800))
        .expect(201);
    assertThat(onHand(manager, ors)).isEqualTo(25);
  }

  // ------------------------------------------------------------------ FRD-2 repacks

  @Test
  @DisplayName(
      "FRD-2: a repack follows its recipe exactly and never makes a controlled input into a"
          + " lower class")
  void repacksFollowTheRecipe() {
    String box = product("Tramadol box of 10 strips", "CONTROLLED", 2_000);
    String strip = product("Tramadol strip", "OTC", 200);
    opening(manager, box, "TR-1", 5, 2_000);
    String breakBulk =
        """
        {"type":"BREAK_BULK","inputs":[{"productUid":"%s","quantity":%d}],
         "output":{"productUid":"%s","quantity":%d}}
        """;
    // No pack link: there is no recipe.
    assertThat(
            manager
                .post("/api/v1/stock/transformations", breakBulk.formatted(box, 1, strip, 10))
                .expect(422)
                .string("$.reason"))
        .isEqualTo("no_recipe");
    admin
        .put(
            "/api/v1/pack-links",
            "{\"packProductUid\":\"%s\",\"unitProductUid\":\"%s\",\"unitsPerPack\":10}"
                .formatted(box, strip))
        .expect(200);
    // One box makes exactly ten strips.
    assertThat(
            manager
                .post("/api/v1/stock/transformations", breakBulk.formatted(box, 1, strip, 12))
                .expect(422)
                .string("$.reason"))
        .isEqualTo("recipe_mismatch");
    assertThat(
            manager
                .post("/api/v1/stock/transformations", breakBulk.formatted(box, 2, strip, 10))
                .expect(422)
                .string("$.reason"))
        .isEqualTo("recipe_mismatch");
    // A controlled box cannot become OTC strips; as controlled strips it can.
    Response downgrade =
        manager
            .post("/api/v1/stock/transformations", breakBulk.formatted(box, 1, strip, 10))
            .expect(422);
    assertThat(downgrade.string("$.reason")).isEqualTo("schedule_downgrade");
    changeSchedule(strip, "CONTROLLED", approve("SCHEDULE_CHANGE", 0));
    manager
        .post("/api/v1/stock/transformations", breakBulk.formatted(box, 1, strip, 10))
        .expect(201);
    assertThat(onHand(manager, strip)).isEqualTo(10);
  }

  // ------------------------------------------------------------------ FRD-6 schedule changes

  @Test
  @DisplayName(
      "FRD-6: a class change needs a manager and is audited; the register lists every product"
          + " ever controlled")
  void scheduleChanges() {
    String codeine = product("Codeine 30mg", "CONTROLLED", 150);
    opening(manager, codeine, "COD-1", 10, 150);
    Response refused = changeSchedule(codeine, "OTC", null).expect(403);
    assertThat(refused.string("$.reason")).isEqualTo("approval_required");
    assertThat(refused.string("$.action")).isEqualTo("SCHEDULE_CHANGE");
    changeSchedule(codeine, "OTC", approve("SCHEDULE_CHANGE", 0)).expect(200);
    assertThat(admin.get("/api/v1/products/" + codeine).expect(200).string("$.data.schedule"))
        .isEqualTo("OTC");

    Response audit =
        admin.get("/api/v1/audit?action=SCHEDULE_CHANGED&entityUid=" + codeine).expect(200);
    assertThat(audit.string("$.data.items[0].before.schedule")).isEqualTo("CONTROLLED");
    assertThat(audit.string("$.data.items[0].after.schedule")).isEqualTo("OTC");

    // Still on the register, with its movements, though OTC now.
    Response register = manager.get("/api/v1/controlled-drugs").expect(200);
    assertThat(
            register.read(
                "$.data.items[?(@.productUid == '" + codeine + "')].controlledNow", List.class))
        .containsExactly(false);
    assertThat(
            manager
                .get("/api/v1/controlled-drugs/" + codeine + "/register")
                .expect(200)
                .read("$.data.items[0].quantityIn", Integer.class))
        .isEqualTo(10);

    // A product import that changes a class is refused without an approval.
    byte[] file =
        ("Code,Name,Type,Generic name,Dosage form,Schedule,Cost,Price\r\n"
                + "%s,Codeine 30mg,MEDICINE,Codeine 30mg,TABLET,CONTROLLED,150,300"
                    .formatted(sku(codeine)))
            .getBytes(StandardCharsets.UTF_8);
    Response imported = admin.upload("/api/v1/products/import", "products.csv", file).expect(200);
    assertThat((Boolean) imported.json().read("$.data.saved")).isFalse();
    assertThat(imported.json().<List<String>>read("$.data.problems[*].reason"))
        .contains("approval_required");
  }

  // ------------------------------------------------------------------ FRD-15 transfers

  @Test
  @DisplayName(
      "FRD-15: the sender cannot receive; units short are the sender's loss at cost, approved above"
          + " the limit; overdue transfers are listed")
  void transfers() {
    String arusha =
        admin
            .post("/api/v1/branches", "{\"code\":\"AR\",\"name\":\"Arusha\"}")
            .expect(201)
            .string("$.data.uid");
    Api managerArusha = manager.in(tenant, arusha);
    Api adminArusha = admin.in(tenant, arusha);
    String insulin = product("Insulin 10ml", "PRESCRIPTION", 10_000);
    opening(manager, insulin, "INS-1", 20, 10_000);

    String first = dispatch(manager, arusha, insulin, 5);
    // The manager who sent it cannot confirm its arrival, at either end.
    Response self = managerArusha.post("/api/v1/transfers/" + first + "/receive", null).expect(422);
    assertThat(self.string("$.reason")).isEqualTo("receiver_is_sender");

    // Four of five arrive: a note is required; 10,000 short is below the limit.
    String four =
        """
        {"note":%s,"lines":[{"productUid":"%s","receivedQuantity":4}]}
        """;
    assertThat(
            adminArusha
                .post("/api/v1/transfers/" + first + "/receive", four.formatted("null", insulin))
                .expect(422)
                .string("$.reason"))
        .isEqualTo("note_required");
    Response received =
        adminArusha
            .post(
                "/api/v1/transfers/" + first + "/receive",
                four.formatted("\"Box crushed in the bus\"", insulin))
            .expect(200);
    assertThat(received.read("$.data.lines[0].receivedQuantity", Integer.class)).isEqualTo(4);
    assertThat(received.string("$.data.receivedBy")).isEqualTo(adminEmail);
    assertThat(onHand(adminArusha, insulin)).isEqualTo(4);
    // The sender's books: 20 − 5 dispatched; the one short comes back and is written off there.
    assertThat(onHand(manager, insulin)).isEqualTo(15);
    Response losses =
        manager
            .get("/api/v1/stock/movements?productUid=" + insulin + "&type=ADJUSTMENT")
            .expect(200);
    assertThat(losses.read("$.data.items[0].quantityChange", Integer.class)).isEqualTo(-1);
    assertThat(losses.string("$.data.items[0].reasonCode")).isEqualTo("THEFT_LOSS");
    assertThat(losses.string("$.data.items[0].note")).contains("Box crushed");
    // The ledger posts the loss against the sending branch.
    eventually(
        () ->
            admin
                    .get("/api/v1/journal")
                    .expect(200)
                    .json()
                    .<List<Object>>read(
                        "$.data.items[?(@.branchUid == '"
                            + hq
                            + "' && @.description =~ /.*THEFT_LOSS.*/)]")
                    .size()
                == 1);

    // Six short at 10,000 is above the limit: a manager approves the shortfall.
    String second = dispatch(manager, arusha, insulin, 8);
    String twoOfEight =
        "{\"note\":\"Missing on arrival\",\"lines\":[{\"productUid\":\"%s\",\"receivedQuantity\":2}]}"
            .formatted(insulin);
    Response shortfall =
        adminArusha.post("/api/v1/transfers/" + second + "/receive", twoOfEight).expect(403);
    assertThat(shortfall.string("$.action")).isEqualTo("TRANSFER_SHORTFALL");
    assertThat(shortfall.read("$.amount", Double.class)).isEqualTo(60_000.0);
    String token =
        admin
            .in(tenant, arusha)
            .post(
                "/api/v1/approvals",
                "{\"action\":\"TRANSFER_SHORTFALL\",\"amount\":60000,\"login\":\"%s\",\"password\":\"%s\"}"
                    .formatted(managerLogin, MANAGER_PASSWORD))
            .expect(201)
            .string("$.data.token");
    adminArusha
        .post("/api/v1/transfers/" + second + "/receive", twoOfEight, "X-Approval", token)
        .expect(200);
    assertThat(onHand(adminArusha, insulin)).isEqualTo(6);
    assertThat(onHand(manager, insulin)).isEqualTo(7);

    // A transfer in transit for ten days is overdue at a week, not at a month.
    String third = dispatch(manager, arusha, insulin, 1);
    backdate(third, 10);
    Response overdue = manager.get("/api/v1/transfers?overdue=true").expect(200);
    assertThat(overdue.json().<List<String>>read("$.data.items[*].uid")).containsExactly(third);
    assertThat(overdue.read("$.data.items[0].daysInTransit", Integer.class)).isEqualTo(10);
    assertThat(
            adminArusha
                .get("/api/v1/transfers?overdue=true&direction=INCOMING&days=7")
                .expect(200)
                .json()
                .<List<String>>read("$.data.items[*].uid"))
        .containsExactly(third);
    assertThat(
            manager
                .get("/api/v1/transfers?overdue=true&days=30")
                .expect(200)
                .json()
                .<List<String>>read("$.data.items[*].uid"))
        .isEmpty();
    assertThat(manager.get("/api/v1/transfers?overdue=true&days=0").expect(422).string("$.reason"))
        .isEqualTo("invalid_days");
  }

  // ------------------------------------------------------------------ helpers

  /** A manager-approval token from the owner, for the manager's request. */
  private String approve(String action, double amount) {
    return manager
        .post(
            "/api/v1/approvals",
            "{\"action\":\"%s\",\"amount\":%s,\"login\":\"%s\",\"password\":\"%s\"}"
                .formatted(action, amount, adminEmail, ADMIN_PASSWORD))
        .expect(201)
        .string("$.data.token");
  }

  private String product(String name, String schedule, int cost) {
    return admin
        .post(
            "/api/v1/products",
            """
            {"name":"%s","genericName":"%s","dosageForm":"TABLET","schedule":"%s",
             "sellingPrice":%d,"costPrice":%d}
            """
                .formatted(name, name, schedule, cost * 2, cost))
        .expect(201)
        .string("$.data.uid");
  }

  private String sku(String product) {
    return admin.get("/api/v1/products/" + product).expect(200).string("$.data.sku");
  }

  /** Changes the product's class through the product form, as the manager would. */
  private Response changeSchedule(String product, String schedule, String approval) {
    Response current = admin.get("/api/v1/products/" + product).expect(200);
    String body =
        """
        {"name":"%s","genericName":"%s","sku":"%s","dosageForm":"TABLET","schedule":"%s",
         "sellingPrice":%s,"costPrice":%s,"active":true,"version":%d}
        """
            .formatted(
                current.string("$.data.name"),
                current.string("$.data.genericName"),
                current.string("$.data.sku"),
                schedule,
                current.read("$.data.sellingPrice", Double.class),
                current.read("$.data.costPrice", Double.class),
                current.read("$.data.version", Integer.class));
    Api editor = manager.in(tenant, null);
    return approval == null
        ? editor.put("/api/v1/products/" + product, body)
        : editor.put("/api/v1/products/" + product, body, "X-Approval", approval);
  }

  private static String openingBody(String product, String batch, int quantity, int cost) {
    return """
        {"productUid":"%s","batchNumber":"%s","expiryDate":"%s","quantity":%d,"unitCost":%d}
        """
        .formatted(product, batch, LocalDate.now().plusYears(1), quantity, cost);
  }

  private static Response opening(Api till, String product, String batch, int quantity, int cost) {
    return till.post("/api/v1/stock/opening", openingBody(product, batch, quantity, cost))
        .expect(201);
  }

  private static String adjustment(String product, String batch, int change, String note) {
    return """
        {"productUid":"%s","batchUid":"%s","quantityChange":%d,"reasonCode":"DAMAGE","note":%s}
        """
        .formatted(product, batch, change, note == null ? "null" : "\"" + note + "\"");
  }

  private static String line(Response sheet, String batch) {
    List<String> uids = sheet.json().read("$.data.items[?(@.batchNumber == '" + batch + "')].uid");
    return uids.getFirst();
  }

  private static int onHand(Api till, String product) {
    List<Integer> quantities =
        till.get("/api/v1/stock/" + product + "/batches")
            .expect(200)
            .json()
            .read("$.data[*].quantity");
    return quantities.stream().mapToInt(Integer::intValue).sum();
  }

  private static String dispatch(Api from, String to, String product, int quantity) {
    return from.post(
            "/api/v1/transfers",
            "{\"toBranchUid\":\"%s\",\"lines\":[{\"productUid\":\"%s\",\"quantity\":%d}]}"
                .formatted(to, product, quantity))
        .expect(201)
        .string("$.data.uid");
  }

  /** Moves a transfer's dispatch back {@code days} days, as if it had long been on the road. */
  private void backdate(String transfer, int days) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (PreparedStatement scope =
          connection.prepareStatement("select set_config('pharmacy.tenant_uid', ?, true)")) {
        scope.setString(1, tenant);
        scope.execute();
      }
      try (PreparedStatement update =
          connection.prepareStatement(
              "update inventory.transfers set created_at = created_at - make_interval(days => ?)"
                  + " where uid = ?::uuid")) {
        update.setInt(1, days);
        update.setString(2, transfer);
        assertThat(update.executeUpdate()).isEqualTo(1);
      }
      connection.commit();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
